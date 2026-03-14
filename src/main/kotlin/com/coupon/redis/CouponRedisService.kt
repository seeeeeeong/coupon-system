package com.coupon.redis

import com.coupon.exception.RedisUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Redis Lua Script 실행 및 키 관리를 담당한다.
 *
 * 키 설계:
 *   {coupon:$couponTemplateId}:stock   → 남은 재고 수량 (String)
 *   {coupon:$couponTemplateId}:issued  → 발급 완료 userId Set
 *
 * 해시태그 {}를 사용하는 이유:
 *   Redis Cluster에서 Lua Script는 모든 키가 동일 슬롯에 있어야 실행 가능하다.
 *   {coupon:$id} 부분이 해시 키가 되어 stock/issued 두 키가 항상 같은 노드에 배치된다.
 *
 * issued Set TTL 전략:
 *   초기화 시 issued Set을 미리 생성하지 않는다.
 *   발급 성공 시 Lua Script 안에서 SADD 후 EXPIREAT로 절대 만료 시각을 설정한다.
 *   이렇게 하면:
 *   - placeholder 버그(빈 Set → 키 자동 삭제 → TTL 소멸) 제거
 *   - 첫 발급 시점과 무관하게 만료 시각 고정
 *   - EXPIREAT는 같은 절대 시각으로 반복 호출해도 의미 불변 → 멱등
 */
@Service
class CouponRedisService(
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        fun stockKey(couponTemplateId: Long) = "{coupon:$couponTemplateId}:stock"
        fun issuedKey(couponTemplateId: Long) = "{coupon:$couponTemplateId}:issued"

        /**
         * Lua Script.
         *
         * KEYS[1] = stock key
         * KEYS[2] = issued set key
         * ARGV[1] = userId
         * ARGV[2] = expireAtEpoch (Unix 초, eventEndAt + buffer의 절대 시각)
         *
         * 반환값:
         *   1  → 성공
         *  -1  → 중복 요청 (이미 issued Set에 존재)
         *  -2  → 품절 (stock <= 0)
         *  -3  → 상태 이상 (stock 키 자체가 없음: 미초기화 / TTL 만료 / 키 유실)
         *
         * stock == nil과 stock <= 0을 분리하는 이유:
         *   nil은 비즈니스 상태(품절)가 아니라 시스템 상태(키 유실/미초기화)다.
         *   이를 SOLD_OUT으로 처리하면 운영 장애가 비즈니스 상태로 위장되어
         *   모니터링에서 감지하기 어려워진다.
         */
        /**
         * Redis best-effort 롤백 스크립트.
         *
         * Outbox 저장 실패 시 Redis에서 재고 차감과 issued 등록을 원복한다.
         * stock 키가 살아 있는 경우에만 INCR하여 TTL 만료 극단 케이스를 방어한다.
         * 롤백 자체도 실패할 수 있으므로 호출부에서 예외를 삼키고 원본 예외를 전파해야 한다.
         *
         * KEYS[1] = stock key
         * KEYS[2] = issued set key
         * ARGV[1] = userId
         */
        val ROLLBACK_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('INCR', KEYS[1])
            end
            redis.call('SREM', KEYS[2], ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java
        )

        val ISSUE_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            local isMember = redis.call('SISMEMBER', KEYS[2], ARGV[1])
            if isMember == 1 then
                return -1
            end

            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then
                return -3
            end
            if stock <= 0 then
                return -2
            end

            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            redis.call('EXPIREAT', KEYS[2], ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.java
        )
    }

    /**
     * 이벤트 시작 전 stock 키만 초기화한다.
     * issued Set은 미리 생성하지 않는다. 발급 성공 시 Lua Script에서 생성한다.
     *
     * stock 키도 동일한 절대 만료 시각(expireAt)을 사용한다.
     * 상대 TTL(Duration)을 쓰면 초기화 시점에 따라 만료 시각이 달라지지만
     * 절대 시각은 항상 eventEndAt + buffer로 고정된다.
     *
     * @param expireAt stock 키의 만료 시각 (eventEndAt + buffer)
     */
    fun initializeStock(couponTemplateId: Long, quantity: Int, expireAt: Instant) {
        val stockKey = stockKey(couponTemplateId)
        val ttl = Duration.between(Instant.now(), expireAt).coerceAtLeast(Duration.ofSeconds(1))

        redisTemplate.opsForValue().set(stockKey, quantity.toString(), ttl)

        log.info(
            "Redis 재고 초기화 완료. couponTemplateId={}, quantity={}, expireAt={}",
            couponTemplateId, quantity, expireAt
        )
    }

    /**
     * Lua Script를 실행하여 원자적으로 재고 차감 및 중복 체크를 수행한다.
     * Redis 장애 시 RedisUnavailableException을 던진다.
     *
     * @param expireAtEpoch issued Set의 절대 만료 시각 (Unix 초)
     */
    fun tryIssue(couponTemplateId: Long, userId: Long, expireAtEpoch: Long): LuaResult {
        val raw = try {
            (redisTemplate.execute(
                ISSUE_SCRIPT,
                listOf(stockKey(couponTemplateId), issuedKey(couponTemplateId)),
                userId.toString(),
                expireAtEpoch.toString()
            ) as Long?) ?: throw RedisUnavailableException()
        } catch (e: RedisUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.error("Redis Lua Script 실행 실패. couponTemplateId={}, userId={}", couponTemplateId, userId, e)
            throw RedisUnavailableException()
        }

        return LuaResult.from(raw)
    }

    /**
     * Outbox 저장 실패 시 Redis 상태를 best-effort로 원복한다.
     *
     * - stock 키 존재 시 INCR (재고 복구)
     * - issued Set에서 userId SREM (중복 기록 제거)
     *
     * 이 롤백 자체도 Redis 장애 시 실패할 수 있다.
     * 실패 시에는 stock/issued 불일치 상태가 유지되며 어드민 수동 보상이 필요하다.
     * 예외는 삼켜서 원본 DB 예외가 호출부까지 전파되도록 한다.
     */
    fun rollbackIssue(couponTemplateId: Long, userId: Long) {
        try {
            redisTemplate.execute(
                ROLLBACK_SCRIPT,
                listOf(stockKey(couponTemplateId), issuedKey(couponTemplateId)),
                userId.toString()
            )
            log.warn("Redis 롤백 완료. couponTemplateId={}, userId={}", couponTemplateId, userId)
        } catch (e: Exception) {
            // 롤백 실패: stock/issued 불일치 상태 유지. 어드민 수동 보상 필요.
            log.error(
                "Redis 롤백 실패. 수동 보상 필요. couponTemplateId={}, userId={}",
                couponTemplateId, userId, e
            )
        }
    }

    fun getStock(couponTemplateId: Long): Long? =
        redisTemplate.opsForValue().get(stockKey(couponTemplateId))?.toLong()
}

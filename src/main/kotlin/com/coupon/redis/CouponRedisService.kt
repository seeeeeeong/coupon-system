package com.coupon.redis

import com.coupon.exception.RedisUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Redis Lua Script 실행 및 키 관리를 담당한다.
 *
 * 키 설계:
 *   coupon:{couponTemplateId}:stock   → 남은 재고 수량 (String)
 *   coupon:{couponTemplateId}:issued  → 발급 완료 userId Set
 *   coupon:{couponTemplateId}:meta    → 쿠폰 템플릿 메타데이터 캐시 (Hash)
 *
 * Lua Script 반환값:
 *   1  → 성공
 *  -1  → 중복 요청
 *  -2  → 품절
 */
@Service
class CouponRedisService(
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        fun stockKey(couponTemplateId: Long) = "coupon:$couponTemplateId:stock"
        fun issuedKey(couponTemplateId: Long) = "coupon:$couponTemplateId:issued"

        const val RESULT_SUCCESS = 1L
        const val RESULT_DUPLICATE = -1L
        const val RESULT_SOLD_OUT = -2L

        /**
         * Lua Script:
         * - 중복 체크 → 재고 확인 → 원자적 차감
         * KEYS[1] = stock key
         * KEYS[2] = issued set key
         * ARGV[1] = userId
         */
        val ISSUE_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            local isMember = redis.call('SISMEMBER', KEYS[2], ARGV[1])
            if isMember == 1 then
                return -1
            end

            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil or stock <= 0 then
                return -2
            end

            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java
        )
    }

    /**
     * 이벤트 시작 전 Redis 키를 초기화한다.
     * stock과 issued Set을 설정하고 TTL을 부여한다.
     * 이미 키가 존재하면 덮어쓴다 (재초기화 지원).
     */
    fun initializeStock(couponTemplateId: Long, quantity: Int, ttl: Duration) {
        val stockKey = stockKey(couponTemplateId)
        val issuedKey = issuedKey(couponTemplateId)

        redisTemplate.opsForValue().set(stockKey, quantity.toString(), ttl)

        // issued Set 초기화: 기존 키 삭제 후 TTL 설정
        redisTemplate.delete(issuedKey)
        // 빈 Set은 TTL 직접 설정 불가 → 더미 값 추가 후 삭제 방식 대신
        // SET 자체에 expire 적용을 위해 빈 Set을 만들고 expire 설정
        redisTemplate.opsForSet().add(issuedKey, "__placeholder__")
        redisTemplate.expire(issuedKey, ttl)
        redisTemplate.opsForSet().remove(issuedKey, "__placeholder__")

        log.info("Redis 재고 초기화 완료. couponTemplateId={}, quantity={}, ttl={}", couponTemplateId, quantity, ttl)
    }

    /**
     * Lua Script를 실행하여 원자적으로 재고 차감 및 중복 체크를 수행한다.
     * Redis 장애 시 RedisUnavailableException을 던진다.
     */
    fun tryIssue(couponTemplateId: Long, userId: Long): Long {
        return try {
            redisTemplate.execute(
                ISSUE_SCRIPT,
                listOf(stockKey(couponTemplateId), issuedKey(couponTemplateId)),
                userId.toString()
            ) ?: throw RedisUnavailableException()
        } catch (e: RedisUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.error("Redis Lua Script 실행 실패. couponTemplateId={}, userId={}", couponTemplateId, userId, e)
            throw RedisUnavailableException()
        }
    }

    fun getStock(couponTemplateId: Long): Long? {
        return redisTemplate.opsForValue().get(stockKey(couponTemplateId))?.toLongOrNull()
    }
}

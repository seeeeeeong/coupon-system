package com.coupon.service

import com.coupon.exception.CouponAlreadyIssuedException
import com.coupon.exception.CouponSoldOutException
import com.coupon.exception.CouponStateInvalidException
import com.coupon.redis.CouponRedisService
import com.coupon.redis.LuaResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.ZoneId

@Service
class CouponIssueService(
    private val couponTemplateService: CouponTemplateService,
    private val couponRedisService: CouponRedisService,
    private val couponOutboxService: CouponOutboxService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 쿠폰 발급 요청 처리.
     *
     * 흐름:
     * 1. API 레벨 검증 (캐시된 template 조회)
     * 2. Redis Lua Script (트랜잭션 밖) → 원자적 재고 차감 + 중복 체크
     * 3. DB 트랜잭션 → Outbox INSERT
     * 4. 202 Accepted
     *
     * 정합성 트레이드오프:
     *   Redis(2)와 DB(3)의 원자적 커밋은 불가능하다.
     *   Redis 먼저 차감하면 DB 실패 시 발급 누락, DB 먼저 저장하면 Redis 실패 시 초과 발급.
     *   초과 발급(재정 손실)이 발급 누락보다 심각하므로 Redis 먼저 차감하는 방향을 선택한다.
     *
     *   DB 저장 실패 시 best-effort 롤백(Redis INCR + SREM)을 시도한다.
     *   롤백 자체도 실패할 수 있으며, 그 경우 어드민 수동 보상이 필요하다.
     */
    fun issue(couponTemplateId: Long, userId: Long) {
        val template = couponTemplateService.getTemplateById(couponTemplateId)
        couponTemplateService.validateEvent(template)

        val expireAtEpoch = template.eventEndAt
            .plusHours(RedisInitService.EXPIRE_BUFFER_HOURS)
            .atZone(ZoneId.systemDefault())
            .toEpochSecond()

        val result = couponRedisService.tryIssue(couponTemplateId, userId, expireAtEpoch)

        return when (result) {
            LuaResult.SUCCESS       -> saveOutboxWithRollback(couponTemplateId, userId)
            LuaResult.DUPLICATE     -> throw CouponAlreadyIssuedException(userId, couponTemplateId)
            LuaResult.SOLD_OUT      -> throw CouponSoldOutException(couponTemplateId)
            LuaResult.STATE_MISSING -> throw CouponStateInvalidException(couponTemplateId)
        }
    }

    private fun saveOutboxWithRollback(couponTemplateId: Long, userId: Long) {
        try {
            couponOutboxService.save(couponTemplateId, userId)
        } catch (e: Exception) {
            log.error(
                "Outbox 저장 실패. Redis best-effort 롤백 시도. couponTemplateId={}, userId={}",
                couponTemplateId, userId, e
            )
            couponRedisService.rollbackIssue(couponTemplateId, userId)
            throw e
        }
    }
}

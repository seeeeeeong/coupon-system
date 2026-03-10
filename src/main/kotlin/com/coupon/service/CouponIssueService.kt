package com.coupon.service

import com.coupon.domain.CouponOutbox
import com.coupon.exception.CouponAlreadyIssuedException
import com.coupon.exception.CouponSoldOutException
import com.coupon.exception.CouponStateInvalidException
import com.coupon.kafka.CouponIssueEvent
import com.coupon.redis.CouponRedisService
import com.coupon.redis.LuaResult
import com.coupon.repository.CouponOutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class CouponIssueService(
    private val couponTemplateService: CouponTemplateService,
    private val couponRedisService: CouponRedisService,
    private val couponOutboxRepository: CouponOutboxRepository
) {
    private val objectMapper = ObjectMapper().apply { registerModule(JavaTimeModule()) }

    /**
     * 쿠폰 발급 요청 처리.
     *
     * 흐름:
     * 1. API 레벨 검증 (캐시된 template 조회)
     * 2. Redis Lua Script (트랜잭션 밖) → 원자적 재고 차감 + 중복 체크
     * 3. DB 트랜잭션 → Outbox insert
     * 4. 202 Accepted
     */
    fun issue(couponTemplateId: Long, userId: Long) {
        val template = couponTemplateService.getTemplateById(couponTemplateId)
        couponTemplateService.validateEvent(template)

        // expireAtEpoch: issued Set의 절대 만료 시각 (Lua EXPIREAT에 전달)
        val expireAtEpoch = template.eventEndAt
            .plusHours(RedisInitService.EXPIRE_BUFFER_HOURS)
            .atZone(ZoneId.systemDefault())
            .toEpochSecond()

        val result = couponRedisService.tryIssue(couponTemplateId, userId, expireAtEpoch)

        // when expression: 모든 LuaResult 케이스를 반드시 처리해야 컴파일됨
        return when (result) {
            LuaResult.SUCCESS      -> saveOutbox(couponTemplateId, userId)
            LuaResult.DUPLICATE    -> throw CouponAlreadyIssuedException(userId, couponTemplateId)
            LuaResult.SOLD_OUT     -> throw CouponSoldOutException(couponTemplateId)
            LuaResult.STATE_MISSING -> throw CouponStateInvalidException(couponTemplateId)
        }
    }

    @Transactional
    fun saveOutbox(couponTemplateId: Long, userId: Long) {
        val requestedAt = LocalDateTime.now()

        val tempPayload = buildPayload(0L, userId, couponTemplateId, requestedAt)
        val outbox = couponOutboxRepository.save(
            CouponOutbox(eventType = "COUPON_ISSUE", payload = tempPayload)
        )

        outbox.payload = buildPayload(outbox.id, userId, couponTemplateId, requestedAt)
        couponOutboxRepository.save(outbox)
    }

    private fun buildPayload(
        eventId: Long,
        userId: Long,
        couponTemplateId: Long,
        requestedAt: LocalDateTime
    ): String = objectMapper.writeValueAsString(
        CouponIssueEvent(eventId, userId, couponTemplateId, requestedAt)
    )
}

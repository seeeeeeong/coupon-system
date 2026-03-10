package com.coupon.service

import com.coupon.domain.CouponOutbox
import com.coupon.exception.CouponAlreadyIssuedException
import com.coupon.exception.CouponSoldOutException
import com.coupon.kafka.CouponIssueEvent
import com.coupon.redis.CouponRedisService
import com.coupon.redis.CouponRedisService.Companion.RESULT_DUPLICATE
import com.coupon.redis.CouponRedisService.Companion.RESULT_SOLD_OUT
import com.coupon.redis.CouponRedisService.Companion.RESULT_SUCCESS
import com.coupon.repository.CouponOutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

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
     *
     * Redis 호출을 트랜잭션 밖에 두는 이유:
     * - Redis는 DB 트랜잭션에 참여하지 않음
     * - 트랜잭션 안에서 Redis 차감 성공 후 DB 실패 시 Redis 롤백 방법 없음
     * - Redis 성공 후 DB 실패는 재고 과소 차감 (초과 발급보다 비즈니스 리스크 낮음)
     */
    fun issue(couponTemplateId: Long, userId: Long) {
        // 1. API 레벨 검증 (캐시 활용)
        val template = couponTemplateService.getTemplateById(couponTemplateId)
        couponTemplateService.validateEvent(template)

        // 2. Redis Lua Script 실행 (트랜잭션 밖)
        // RedisUnavailableException 발생 시 상위로 전파 → 503
        val result = couponRedisService.tryIssue(couponTemplateId, userId)

        when (result) {
            RESULT_DUPLICATE -> throw CouponAlreadyIssuedException(userId, couponTemplateId)
            RESULT_SOLD_OUT  -> throw CouponSoldOutException(couponTemplateId)
            RESULT_SUCCESS   -> saveOutbox(couponTemplateId, userId)
        }
    }

    /**
     * Outbox insert를 별도 메서드로 분리하여 @Transactional 범위를 명확히 한다.
     *
     * eventId 순서 문제 해결:
     * - payload에 eventId(outbox PK)가 필요하지만 AUTO_INCREMENT이므로 INSERT 이후에만 알 수 있음
     * - 1차 INSERT: eventId=0인 임시 payload로 저장
     * - ID 확보 후 payload를 실제 outbox.id로 UPDATE
     * - 두 작업이 동일 트랜잭션 내에서 이루어짐
     */
    @Transactional
    fun saveOutbox(couponTemplateId: Long, userId: Long) {
        val requestedAt = LocalDateTime.now()

        // 1차 저장: eventId는 placeholder(0)
        val tempPayload = buildPayload(eventId = 0L, userId = userId, couponTemplateId = couponTemplateId, requestedAt = requestedAt)
        val outbox = couponOutboxRepository.save(
            CouponOutbox(
                eventType = "COUPON_ISSUE",
                payload = tempPayload
            )
        )

        // ID 확보 후 payload 업데이트
        outbox.payload = buildPayload(
            eventId = outbox.id,
            userId = userId,
            couponTemplateId = couponTemplateId,
            requestedAt = requestedAt
        )
        couponOutboxRepository.save(outbox)
    }

    private fun buildPayload(
        eventId: Long,
        userId: Long,
        couponTemplateId: Long,
        requestedAt: LocalDateTime
    ): String {
        val event = CouponIssueEvent(
            eventId = eventId,
            userId = userId,
            couponTemplateId = couponTemplateId,
            requestedAt = requestedAt
        )
        return objectMapper.writeValueAsString(event)
    }
}

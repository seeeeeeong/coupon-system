package com.coupon.service

import com.coupon.domain.CouponOutbox
import com.coupon.kafka.OutboxPayload
import com.coupon.repository.CouponOutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Outbox 저장 전담 서비스.
 *
 * CouponIssueService에서 직접 couponOutboxRepository.save()를 호출하면
 * self-invocation으로 @Transactional이 적용되지 않는다.
 * 별도 빈으로 분리하여 Spring AOP 프록시를 통해 트랜잭션이 올바르게 적용되도록 한다.
 */
@Service
class CouponOutboxService(
    private val couponOutboxRepository: CouponOutboxRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun save(couponTemplateId: Long, userId: Long) {
        val payload = objectMapper.writeValueAsString(
            OutboxPayload(userId, couponTemplateId, LocalDateTime.now())
        )
        couponOutboxRepository.save(
            CouponOutbox(eventType = "COUPON_ISSUE", payload = payload)
        )
    }
}

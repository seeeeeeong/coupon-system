package com.coupon.kafka

import java.time.LocalDateTime

/**
 * Outbox 테이블 payload 직렬화 전용 DTO.
 *
 * CouponIssueEvent와 분리하는 이유:
 * - 저장 시점에는 outbox PK(eventId)를 알 수 없다.
 * - relay 시점에 outbox.id를 eventId로 주입하여 CouponIssueEvent를 생성한다.
 * - 이중 저장(INSERT + UPDATE) 없이 단일 INSERT로 완료.
 */
data class OutboxPayload(
    val userId: Long,
    val couponTemplateId: Long,
    val requestedAt: LocalDateTime
)

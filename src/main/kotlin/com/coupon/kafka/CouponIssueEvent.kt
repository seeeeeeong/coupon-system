package com.coupon.kafka

import java.time.LocalDateTime

data class CouponIssueEvent(
    val eventId: Long,           // coupon_outbox PK (추적 및 역참조용)
    val userId: Long,
    val couponTemplateId: Long,
    val requestedAt: LocalDateTime
)

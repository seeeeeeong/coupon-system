package com.coupon.controller.dto

import java.time.LocalDateTime

data class CouponTemplateCreateRequest(
    val name: String,
    val discountAmount: Int,
    val totalQuantity: Int,
    val eventStartAt: LocalDateTime,
    val eventEndAt: LocalDateTime
)

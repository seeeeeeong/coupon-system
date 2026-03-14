package com.coupon.controller.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CouponTemplateCreateRequest(
    @field:NotBlank(message = "쿠폰 이름은 필수입니다")
    @field:Size(max = 100, message = "쿠폰 이름은 100자 이내여야 합니다")
    val name: String,

    @field:Positive(message = "할인 금액은 양수여야 합니다")
    val discountAmount: Int,

    @field:Positive(message = "수량은 양수여야 합니다")
    val totalQuantity: Int,

    val eventStartAt: LocalDateTime,
    val eventEndAt: LocalDateTime
)

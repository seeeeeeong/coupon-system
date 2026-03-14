package com.coupon.controller.dto

import jakarta.validation.constraints.Positive

data class CouponIssueRequest(
    @field:Positive(message = "userId는 양수여야 합니다")
    val userId: Long
)

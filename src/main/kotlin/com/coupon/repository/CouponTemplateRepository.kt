package com.coupon.repository

import com.coupon.domain.CouponTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface CouponTemplateRepository : JpaRepository<CouponTemplate, Long>

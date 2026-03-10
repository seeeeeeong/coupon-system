package com.coupon.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "coupon_template")
class CouponTemplate(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false)
    val discountAmount: Int,

    @Column(nullable = false)
    val totalQuantity: Int,

    @Column(nullable = false)
    val eventStartAt: LocalDateTime,

    @Column(nullable = false)
    val eventEndAt: LocalDateTime,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

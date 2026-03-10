package com.coupon.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "coupons",
    uniqueConstraints = [UniqueConstraint(name = "uq_user_coupon", columnNames = ["user_id", "coupon_template_id"])]
)
class Coupon(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val userId: Long,

    @Column(nullable = false)
    val couponTemplateId: Long,

    @Column(nullable = false)
    val issuedAt: LocalDateTime = LocalDateTime.now()
)

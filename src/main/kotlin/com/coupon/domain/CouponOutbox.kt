package com.coupon.domain

import jakarta.persistence.*
import java.time.LocalDateTime

enum class OutboxStatus {
    INIT, PUBLISHED, FAILED
}

@Entity
@Table(name = "coupon_outbox")
class CouponOutbox(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50)
    val eventType: String,

    @Column(nullable = false, columnDefinition = "JSON")
    var payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.INIT,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    fun markPublished() {
        this.status = OutboxStatus.PUBLISHED
        this.updatedAt = LocalDateTime.now()
    }

    fun incrementRetry(maxRetry: Int) {
        this.retryCount++
        if (this.retryCount >= maxRetry) {
            this.status = OutboxStatus.FAILED
        }
        this.updatedAt = LocalDateTime.now()
    }
}

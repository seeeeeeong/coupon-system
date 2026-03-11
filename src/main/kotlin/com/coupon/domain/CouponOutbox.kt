package com.coupon.domain

import jakarta.persistence.*
import java.time.LocalDateTime

enum class OutboxStatus {
    INIT,
    PUBLISHING,  // relay가 claim한 상태. 서버 다운 시 recovery scheduler가 INIT으로 복구
    PUBLISHED,
    FAILED
}

@Entity
@Table(name = "coupon_outbox")
class CouponOutbox(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50)
    val eventType: String,

    // columnDefinition 생략: H2(테스트) Hibernate DDL → CLOB, MySQL(운영) schema.sql → JSON
    @Column(nullable = false, length = 65535)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.INIT,

    @Column(nullable = false)
    var retryCount: Int = 0,

    /**
     * relay 인스턴스 세대 식별자 (UUID).
     * PUBLISHING 상태로 claim할 때 생성되고, 상태 전이 완료 시 null로 초기화.
     * bulkMarkPublished/bulkReturnToInit/bulkMarkFailed에서 이 값을 WHERE 조건으로 사용하여
     * stale claim(recovery 후 B가 재claim한 row를 A가 건드리는 케이스)을 막는다.
     */
    @Column(length = 36)
    var claimToken: String? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    fun markPublished() {
        this.status = OutboxStatus.PUBLISHED
        this.updatedAt = LocalDateTime.now()
    }

    fun markFailed() {
        this.status = OutboxStatus.FAILED
        this.updatedAt = LocalDateTime.now()
    }

    fun returnToInit() {
        this.retryCount++
        this.status = OutboxStatus.INIT
        this.updatedAt = LocalDateTime.now()
    }

    fun isMaxRetryExceeded(maxRetry: Int) = retryCount >= maxRetry
}

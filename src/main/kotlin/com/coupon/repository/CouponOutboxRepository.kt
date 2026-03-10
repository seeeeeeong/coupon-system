package com.coupon.repository

import com.coupon.domain.CouponOutbox
import com.coupon.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponOutboxRepository : JpaRepository<CouponOutbox, Long> {

    /**
     * SKIP LOCKED 네이티브 쿼리.
     * JPA @Lock + QueryHint로는 SKIP LOCKED를 안정적으로 지원하지 않아 네이티브 쿼리를 사용한다.
     *
     * FOR UPDATE SKIP LOCKED 동작:
     * - 이미 다른 트랜잭션이 잠근 행은 건너뜀
     * - 여러 인스턴스가 동시에 실행해도 같은 행을 중복 처리하지 않음
     * - 수평 확장(다중 인스턴스) 지원
     *
     * 인덱스 활용: idx_status_id (status, id) → status 필터링 + id 정렬 비용 최소화
     */
    @Query(
        value = """
            SELECT * FROM coupon_outbox
            WHERE status = :status
              AND retry_count < :maxRetry
            ORDER BY id ASC
            LIMIT :limitSize
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findBatchForRelay(
        @Param("status") status: String,
        @Param("maxRetry") maxRetry: Int,
        @Param("limitSize") limitSize: Int
    ): List<CouponOutbox>
}

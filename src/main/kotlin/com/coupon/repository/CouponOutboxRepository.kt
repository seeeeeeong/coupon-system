package com.coupon.repository

import com.coupon.domain.CouponOutbox
import com.coupon.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface CouponOutboxRepository : JpaRepository<CouponOutbox, Long> {

    /**
     * INIT 상태 row를 조회하고 즉시 PUBLISHING으로 claim한다.
     * 같은 트랜잭션 안에서 SELECT + UPDATE가 실행되므로
     * 트랜잭션 커밋 이후 다른 인스턴스가 같은 row를 pickup할 수 없다.
     *
     * SKIP LOCKED: 이미 다른 트랜잭션이 잠근 row는 건너뜀 → 다중 인스턴스 동시 실행 안전
     * status = 'INIT' 조건: 이미 PUBLISHING/PUBLISHED인 row는 조회 대상 제외
     * idx_status_id 인덱스 활용: status 필터링 + id 순서 정렬 비용 최소화
     */
    @Query(
        value = """
            SELECT * FROM coupon_outbox
            WHERE status = 'INIT'
              AND retry_count < :maxRetry
            ORDER BY id ASC
            LIMIT :limitSize
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findBatchForClaim(
        @Param("maxRetry") maxRetry: Int,
        @Param("limitSize") limitSize: Int
    ): List<CouponOutbox>

    /**
     * claim한 row를 PUBLISHING + claim_token으로 마킹.
     * findBatchForClaim과 동일 트랜잭션 내에서 실행 → 중복 pickup 방지.
     * claim_token은 이후 상태 전이 쿼리의 WHERE 조건으로 사용 (세대 충돌 방지).
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.PUBLISHING,
            o.claimToken = :claimToken,
            o.updatedAt = :now
        WHERE o.id IN :ids
    """)
    fun bulkMarkPublishing(
        @Param("ids") ids: List<Long>,
        @Param("claimToken") claimToken: String,
        @Param("now") now: LocalDateTime
    )

    /**
     * 성공한 row를 PUBLISHED로 벌크 업데이트.
     * WHERE claim_token = :claimToken 조건: stale claim의 update 차단.
     * A가 claim 후 hang → recovery가 INIT 복구 → B가 재claim → A가 늦게 도착해도 0 rows updated.
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.PUBLISHED,
            o.claimToken = null,
            o.updatedAt = :now
        WHERE o.id IN :ids AND o.claimToken = :claimToken
    """)
    fun bulkMarkPublished(
        @Param("ids") ids: List<Long>,
        @Param("claimToken") claimToken: String,
        @Param("now") now: LocalDateTime
    )

    /**
     * 실패한 row를 INIT으로 복귀 + retry_count 증가.
     * WHERE claim_token = :claimToken 조건: stale claim 차단.
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.INIT,
            o.retryCount = o.retryCount + 1,
            o.claimToken = null,
            o.updatedAt = :now
        WHERE o.id IN :ids AND o.claimToken = :claimToken
    """)
    fun bulkReturnToInit(
        @Param("ids") ids: List<Long>,
        @Param("claimToken") claimToken: String,
        @Param("now") now: LocalDateTime
    )

    /**
     * 최종 실패 처리 (retry 소진, payload 파싱 불가 등).
     * retry_count++: 마지막 시도 횟수까지 정확히 기록 → 운영 지표 및 장애 분석 신뢰도 확보.
     * WHERE claim_token = :claimToken 조건: stale claim 차단.
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.FAILED,
            o.retryCount = o.retryCount + 1,
            o.claimToken = null,
            o.updatedAt = :now
        WHERE o.id IN :ids AND o.claimToken = :claimToken
    """)
    fun bulkMarkFailed(
        @Param("ids") ids: List<Long>,
        @Param("claimToken") claimToken: String,
        @Param("now") now: LocalDateTime
    )

    /**
     * FATAL 즉시 영구 실패 처리 (직렬화 불가, 존재하지 않는 토픽 등).
     * retry_count 유지: 재시도를 시도조차 하지 않은 상태이므로 카운트를 올리지 않는다.
     * RETRYABLE_UNKNOWN 소진(bulkMarkFailed)과 구분하여 장애 원인 분류를 명확히 한다.
     * WHERE claim_token = :claimToken 조건: stale claim 차단.
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.FAILED,
            o.claimToken = null,
            o.updatedAt = :now
        WHERE o.id IN :ids AND o.claimToken = :claimToken
    """)
    fun bulkMarkFatalFailed(
        @Param("ids") ids: List<Long>,
        @Param("claimToken") claimToken: String,
        @Param("now") now: LocalDateTime
    )

    /**
     * stuck row 복구 (retry budget 남음): PUBLISHING → INIT + retry_count++.
     * retry budget을 소모해야 무한 복구 루프를 막을 수 있다.
     * retry_count + 1 < maxRetry 조건으로 소진 직전까지만 복구.
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.INIT,
            o.retryCount = o.retryCount + 1,
            o.claimToken = null,
            o.updatedAt = :now
        WHERE o.status = com.coupon.domain.OutboxStatus.PUBLISHING
          AND o.updatedAt < :stuckBefore
          AND o.retryCount + 1 < :maxRetry
    """)
    fun recoverStuckToInit(
        @Param("now") now: LocalDateTime,
        @Param("stuckBefore") stuckBefore: LocalDateTime,
        @Param("maxRetry") maxRetry: Int
    )

    /**
     * stuck row 복구 (retry budget 소진): PUBLISHING → FAILED + retry_count++.
     * retry_count + 1 >= maxRetry: 더 이상 재시도 없이 영구 실패 처리.
     */
    @Modifying
    @Query("""
        UPDATE CouponOutbox o
        SET o.status = com.coupon.domain.OutboxStatus.FAILED,
            o.retryCount = o.retryCount + 1,
            o.claimToken = null,
            o.updatedAt = :now
        WHERE o.status = com.coupon.domain.OutboxStatus.PUBLISHING
          AND o.updatedAt < :stuckBefore
          AND o.retryCount + 1 >= :maxRetry
    """)
    fun failStuckExhausted(
        @Param("now") now: LocalDateTime,
        @Param("stuckBefore") stuckBefore: LocalDateTime,
        @Param("maxRetry") maxRetry: Int
    )
}

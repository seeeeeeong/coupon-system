package com.coupon.scheduler

import com.coupon.domain.CouponOutbox
import com.coupon.domain.OutboxStatus
import com.coupon.kafka.CouponEventProducer
import com.coupon.kafka.CouponIssueEvent
import com.coupon.kafka.ProduceResult
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponOutboxRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Outbox Relay 통합 테스트.
 *
 * H2 DB에 outbox rows를 직접 저장하고 relay() 실행 후 DB 상태를 검증한다.
 * CouponEventProducer: MockBean (Kafka 브로커 불필요)
 * CouponRedisService: MockBean (Redis 브로커 불필요)
 *
 * 검증 시나리오:
 *  1. 정상 발행 → PUBLISHED, claimToken null
 *  2. RETRYABLE_UNKNOWN (timeout/일시적 오류) → INIT + retryCount++
 *  3. RETRYABLE_UNKNOWN + retry 소진 → FAILED, retryCount 정확히 반영
 *  4. FATAL → FAILED 즉시, retryCount 유지 (재시도 시도 자체가 없었으므로)
 *  5. payload 파싱 실패 → FAILED 즉시 (sendAsync 호출 없음), retryCount++
 *  6. 빈 배치 → sendAsync 호출 없음
 *  7. 혼합 (성공/실패 공존) → 각각 올바른 상태
 *  8. stuck row 복구 (retry budget 남음) → INIT + retryCount++
 *  9. stuck row retry 소진 → FAILED
 * 10. claim_token 세대 충돌 방지 → stale token의 update는 0 rows
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxRelaySchedulerTest {

    @Autowired
    lateinit var relayScheduler: OutboxRelayScheduler

    @Autowired
    lateinit var outboxUpdateService: OutboxUpdateService

    @Autowired
    lateinit var recoveryScheduler: OutboxRecoveryScheduler

    @Autowired
    lateinit var outboxRepository: CouponOutboxRepository

    @MockBean
    lateinit var producer: CouponEventProducer

    @MockBean
    lateinit var couponRedisService: CouponRedisService

    @BeforeEach
    fun setUp() {
        outboxRepository.deleteAll()
    }

    // ─────────────────────────────────────────────
    // 1. 정상 발행
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("SUCCESS → PUBLISHED, retryCount 변화 없음, claimToken null")
    fun `정상 발행`() {
        val outbox = outboxRepository.save(initOutbox())
        whenever(producer.sendAsync(any<CouponIssueEvent>()))
            .thenReturn(CompletableFuture.completedFuture(ProduceResult.SUCCESS))

        relayScheduler.relay()

        val result = outboxRepository.findById(outbox.id).get()
        assertThat(result.status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(result.retryCount).isEqualTo(0)
        assertThat(result.claimToken).isNull()
    }

    // ─────────────────────────────────────────────
    // 2. RETRYABLE_UNKNOWN: INIT 복귀 + retryCount++
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("RETRYABLE_UNKNOWN → INIT 복귀 + retryCount 증가, claimToken null")
    fun `RETRYABLE_UNKNOWN 재시도 가능`() {
        val outbox = outboxRepository.save(initOutbox(retryCount = 0))
        whenever(producer.sendAsync(any<CouponIssueEvent>()))
            .thenReturn(CompletableFuture.completedFuture(ProduceResult.RETRYABLE_UNKNOWN))

        relayScheduler.relay()

        val result = outboxRepository.findById(outbox.id).get()
        assertThat(result.status).isEqualTo(OutboxStatus.INIT)
        assertThat(result.retryCount).isEqualTo(1)
        assertThat(result.claimToken).isNull()
    }

    // ─────────────────────────────────────────────
    // 3. RETRYABLE_UNKNOWN + retry 소진: FAILED + retryCount 정확히 반영
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("RETRYABLE_UNKNOWN + retry 소진 → FAILED, 마지막 시도 횟수까지 retryCount 반영")
    fun `RETRYABLE_UNKNOWN retry 소진 FAILED`() {
        val maxRetry = 5
        val outbox = outboxRepository.save(initOutbox(retryCount = maxRetry - 1))
        whenever(producer.sendAsync(any<CouponIssueEvent>()))
            .thenReturn(CompletableFuture.completedFuture(ProduceResult.RETRYABLE_UNKNOWN))

        relayScheduler.relay()

        val result = outboxRepository.findById(outbox.id).get()
        assertThat(result.status).isEqualTo(OutboxStatus.FAILED)
        assertThat(result.retryCount).isEqualTo(maxRetry)
        assertThat(result.claimToken).isNull()
    }

    // ─────────────────────────────────────────────
    // 4. FATAL: 즉시 FAILED, retryCount 유지
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("FATAL → FAILED 즉시, retryCount 변화 없음 (재시도 시도 자체가 없었으므로)")
    fun `FATAL 즉시 FAILED`() {
        val outbox = outboxRepository.save(initOutbox(retryCount = 0))
        whenever(producer.sendAsync(any<CouponIssueEvent>()))
            .thenReturn(CompletableFuture.completedFuture(ProduceResult.FATAL))

        relayScheduler.relay()

        val result = outboxRepository.findById(outbox.id).get()
        assertThat(result.status).isEqualTo(OutboxStatus.FAILED)
        assertThat(result.retryCount).isEqualTo(0)
        assertThat(result.claimToken).isNull()
    }

    // ─────────────────────────────────────────────
    // 5. payload 파싱 실패: 즉시 FAILED + retryCount++ (sendAsync 호출 없음)
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("payload 파싱 실패 → FAILED 즉시, sendAsync 호출 없음, retryCount++")
    fun `파싱 실패 즉시 FAILED`() {
        val outbox = outboxRepository.save(
            CouponOutbox(eventType = "COUPON_ISSUE", payload = "invalid-json{{{")
        )

        relayScheduler.relay()

        val result = outboxRepository.findById(outbox.id).get()
        assertThat(result.status).isEqualTo(OutboxStatus.FAILED)
        assertThat(result.retryCount).isEqualTo(1)
        assertThat(result.claimToken).isNull()
        verify(producer, never()).sendAsync(any<CouponIssueEvent>())
    }

    // ─────────────────────────────────────────────
    // 6. 빈 배치: sendAsync 호출 없음
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("처리할 INIT row 없으면 sendAsync 호출하지 않음")
    fun `빈 배치 sendAsync 호출 없음`() {
        relayScheduler.relay()

        verify(producer, never()).sendAsync(any<CouponIssueEvent>())
    }

    // ─────────────────────────────────────────────
    // 7. 혼합: SUCCESS 1건 + RETRYABLE_UNKNOWN 1건
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("SUCCESS/RETRYABLE_UNKNOWN 혼합 배치: 각각 올바른 상태로 전이")
    fun `혼합 배치 처리`() {
        val successOutbox = outboxRepository.save(initOutbox())
        val failOutbox = outboxRepository.save(initOutbox())

        whenever(producer.sendAsync(any<CouponIssueEvent>()))
            .thenReturn(CompletableFuture.completedFuture(ProduceResult.SUCCESS))
            .thenReturn(CompletableFuture.completedFuture(ProduceResult.RETRYABLE_UNKNOWN))

        relayScheduler.relay()

        val successResult = outboxRepository.findById(successOutbox.id).get()
        val failResult = outboxRepository.findById(failOutbox.id).get()

        assertThat(successResult.status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(failResult.status).isEqualTo(OutboxStatus.INIT)
        assertThat(failResult.retryCount).isEqualTo(1)
        verify(producer, times(2)).sendAsync(any<CouponIssueEvent>())
    }

    // ─────────────────────────────────────────────
    // 8. stuck row 복구: PUBLISHING → INIT + retryCount++
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("PUBLISHING stuck row → RecoveryScheduler가 INIT 복구, retryCount 증가")
    fun `stuck row INIT 복구`() {
        outboxRepository.save(
            CouponOutbox(
                eventType = "COUPON_ISSUE",
                payload = validPayload(),
                status = OutboxStatus.PUBLISHING,
                retryCount = 0,
                updatedAt = LocalDateTime.now().minusMinutes(6)
            )
        )

        recoveryScheduler.recover()

        val results = outboxRepository.findAll()
        assertThat(results).allSatisfy { outbox ->
            assertThat(outbox.status).isEqualTo(OutboxStatus.INIT)
            assertThat(outbox.retryCount).isEqualTo(1)
            assertThat(outbox.claimToken).isNull()
        }
    }

    // ─────────────────────────────────────────────
    // 9. stuck row 복구 (retry 소진): PUBLISHING → FAILED
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("retry 소진된 PUBLISHING stuck row → FAILED")
    fun `stuck row retry 소진 FAILED`() {
        val maxRetry = 5
        outboxRepository.save(
            CouponOutbox(
                eventType = "COUPON_ISSUE",
                payload = validPayload(),
                status = OutboxStatus.PUBLISHING,
                retryCount = maxRetry - 1,
                updatedAt = LocalDateTime.now().minusMinutes(6)
            )
        )

        recoveryScheduler.recover()

        val results = outboxRepository.findAll()
        assertThat(results).allSatisfy { outbox ->
            assertThat(outbox.status).isEqualTo(OutboxStatus.FAILED)
            assertThat(outbox.retryCount).isEqualTo(maxRetry)
            assertThat(outbox.claimToken).isNull()
        }
    }

    // ─────────────────────────────────────────────
    // 10. claim_token 세대 충돌 방지
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("세대 충돌: stale claim_token의 update는 row를 건드리지 않음")
    fun `claim_token 세대 충돌 방지`() {
        outboxRepository.save(initOutbox())

        val claimedA = outboxUpdateService.fetchAndClaim(10, 5)
        val tokenA = claimedA.claimToken
        assertThat(claimedA.outboxes).hasSize(1)

        val outboxId = claimedA.outboxes[0].id

        // stale token으로 update → 0 rows
        outboxUpdateService.applyResults(
            successIds = listOf(outboxId),
            retryableIds = emptyList(),
            exhaustedIds = emptyList(),
            fatalIds = emptyList(),
            parseFailedIds = emptyList(),
            claimToken = "stale-token-B"
        )

        val afterStaleUpdate = outboxRepository.findById(outboxId).get()
        assertThat(afterStaleUpdate.status).isEqualTo(OutboxStatus.PUBLISHING)
        assertThat(afterStaleUpdate.claimToken).isEqualTo(tokenA)

        // 올바른 tokenA로 update → PUBLISHED
        outboxUpdateService.applyResults(
            successIds = listOf(outboxId),
            retryableIds = emptyList(),
            exhaustedIds = emptyList(),
            fatalIds = emptyList(),
            parseFailedIds = emptyList(),
            claimToken = tokenA
        )

        val afterCorrectUpdate = outboxRepository.findById(outboxId).get()
        assertThat(afterCorrectUpdate.status).isEqualTo(OutboxStatus.PUBLISHED)
        assertThat(afterCorrectUpdate.claimToken).isNull()
    }

    // ─────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────
    private fun initOutbox(retryCount: Int = 0) = CouponOutbox(
        eventType = "COUPON_ISSUE",
        payload = validPayload(),
        retryCount = retryCount
    )

    private fun validPayload() = """{"userId":1,"couponTemplateId":1,"requestedAt":"2026-01-01T00:00:00"}"""
}

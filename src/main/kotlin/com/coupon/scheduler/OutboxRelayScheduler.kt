package com.coupon.scheduler

import com.coupon.domain.CouponOutbox
import com.coupon.kafka.CouponEventProducer
import com.coupon.kafka.CouponIssueEvent
import com.coupon.kafka.OutboxPayload
import com.coupon.kafka.ProduceResult
import com.coupon.repository.CouponOutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * fetchAndClaim이 반환하는 배치. claimToken과 outbox 목록을 함께 전달한다.
 */
data class ClaimedBatch(
    val claimToken: String,
    val outboxes: List<CouponOutbox>
)

/**
 * Outbox Relay Scheduler.
 *
 * INIT → PUBLISHING(claim) → Kafka 비동기 배치 전송 → PUBLISHED / INIT(재시도) / FAILED.
 *
 * 트랜잭션 분리 전략:
 *   트랜잭션1 (fetchAndClaim): SELECT FOR UPDATE SKIP LOCKED + UPDATE PUBLISHING + claimToken → 즉시 커밋
 *   네트워크 I/O: 배치 내 모든 메시지를 비동기로 동시 전송 (sum(RTT) → max(RTT))
 *   트랜잭션2 (applyResults): 수집된 결과로 벌크 UPDATE (WHERE claim_token = :claimToken)
 *
 * 비동기 배치 설계:
 *   기존: send().get(5s) 순차 → 100개 × RTT = 직렬 병목
 *   변경: send() 100개 동시 발사 → allOf().get(batchCollectTimeout) 한 번 대기
 *   batchCollectTimeout > delivery.timeout.ms: 정상 환경에서 producer가 먼저 완료될 가능성을 높임.
 *   collect timeout은 safety net 역할을 한다.
 */
@Component
class OutboxRelayScheduler(
    private val outboxUpdateService: OutboxUpdateService,
    private val producer: CouponEventProducer,
    private val objectMapper: ObjectMapper,
    @Value("\${coupon.outbox.relay.batch-size}") private val batchSize: Int,
    @Value("\${coupon.outbox.relay.max-retry}") private val maxRetry: Int,
    @Value("\${coupon.outbox.relay.batch-collect-timeout-seconds}") private val batchCollectTimeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${coupon.outbox.scheduler.fixed-delay}")
    fun relay() {
        // 트랜잭션1: SELECT INIT + UPDATE PUBLISHING + claimToken 생성 → 즉시 커밋
        val claimed = outboxUpdateService.fetchAndClaim(batchSize, maxRetry)
        if (claimed.outboxes.isEmpty()) return

        log.debug("Outbox relay 시작. claimToken={}, batch size={}", claimed.claimToken, claimed.outboxes.size)

        val outboxById = claimed.outboxes.associateBy { it.id }
        val parseFailedIds = mutableListOf<Long>()

        // 1. 파싱 후 비동기 send를 모두 날림 (순차 대기 없음)
        val futureMap = mutableMapOf<Long, CompletableFuture<ProduceResult>>()
        claimed.outboxes.forEach { outbox ->
            val event = parseEvent(outbox)
            if (event == null) {
                parseFailedIds.add(outbox.id)
            } else {
                futureMap[outbox.id] = producer.sendAsync(event)
            }
        }

        // 2. 배치 단위로 한 번에 결과 수집
        //    delivery.timeout.ms(10s) < batchCollectTimeoutSeconds(15s) 이므로
        //    정상 환경에서 producer가 먼저 완료될 가능성을 높인다.
        //    (thread scheduling, JVM pause 등으로 순서 역전 가능성은 남아 있음)
        //    batchCollectTimeout은 설정 오류나 극단적 환경에서의 safety net.
        if (futureMap.isNotEmpty()) {
            try {
                CompletableFuture.allOf(*futureMap.values.toTypedArray())
                    .get(batchCollectTimeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                log.warn(
                    "배치 collect timeout ({}s). 미완료 future는 RETRYABLE_UNKNOWN으로 처리.",
                    batchCollectTimeoutSeconds
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("relay 스레드 인터럽트. 미완료 future는 RETRYABLE_UNKNOWN으로 처리.")
            }
        }

        // 3. 결과 분류
        val successIds = mutableListOf<Long>()
        val retryableIds = mutableListOf<Long>()
        val exhaustedIds = mutableListOf<Long>()
        val fatalIds = mutableListOf<Long>()

        futureMap.forEach { (id, future) ->
            // isDone이 false면 collect timeout으로 아직 미완료 → RETRYABLE_UNKNOWN
            // ?: RETRYABLE_UNKNOWN: Java CompletableFuture nullable 반환 방어
            val result = if (future.isDone) future.getNow(ProduceResult.RETRYABLE_UNKNOWN)
                             ?: ProduceResult.RETRYABLE_UNKNOWN
                         else ProduceResult.RETRYABLE_UNKNOWN

            val outbox = outboxById[id]!!
            when (result) {
                ProduceResult.SUCCESS -> successIds.add(id)
                ProduceResult.RETRYABLE_UNKNOWN -> {
                    if (outbox.retryCount + 1 >= maxRetry) exhaustedIds.add(id)
                    else retryableIds.add(id)
                }
                ProduceResult.FATAL -> fatalIds.add(id)
            }
        }

        // 4. 트랜잭션2: 벌크 UPDATE (claimToken 조건으로 stale update 차단)
        outboxUpdateService.applyResults(successIds, retryableIds, exhaustedIds, fatalIds, parseFailedIds, claimed.claimToken)

        if (exhaustedIds.isNotEmpty() || fatalIds.isNotEmpty() || parseFailedIds.isNotEmpty()) {
            log.error(
                "[ALERT] Outbox 영구 실패. 수동 처리 필요. exhausted={}, fatal={}, parseFailed={}",
                exhaustedIds, fatalIds, parseFailedIds
            )
            // 실제 운영: Slack/PagerDuty 알림 연동 지점
        }

        log.debug(
            "Outbox relay 완료. claimToken={}, success={}, retryable={}, exhausted={}, fatal={}, parseFailed={}",
            claimed.claimToken, successIds.size, retryableIds.size, exhaustedIds.size, fatalIds.size, parseFailedIds.size
        )
    }

    private fun parseEvent(outbox: CouponOutbox): CouponIssueEvent? {
        return try {
            val payload = objectMapper.readValue(outbox.payload, OutboxPayload::class.java)
            CouponIssueEvent(
                eventId = outbox.id,
                userId = payload.userId,
                couponTemplateId = payload.couponTemplateId,
                requestedAt = payload.requestedAt
            )
        } catch (e: Exception) {
            log.error("Outbox payload 파싱 실패. id={}, payload={}", outbox.id, outbox.payload, e)
            null
        }
    }
}

/**
 * Relay 결과 상태 업데이트를 별도 빈으로 분리.
 * self-invocation 시 @Transactional이 동작하지 않으므로 별도 컴포넌트로 분리.
 */
@Component
class OutboxUpdateService(
    private val outboxRepository: CouponOutboxRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun fetchAndClaim(batchSize: Int, maxRetry: Int): ClaimedBatch {
        val batch = outboxRepository.findBatchForClaim(maxRetry, batchSize)
        if (batch.isEmpty()) return ClaimedBatch(claimToken = "", outboxes = emptyList())

        val claimToken = UUID.randomUUID().toString()
        outboxRepository.bulkMarkPublishing(batch.map { it.id }, claimToken, LocalDateTime.now())
        return ClaimedBatch(claimToken, batch)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyResults(
        successIds: List<Long>,
        retryableIds: List<Long>,
        exhaustedIds: List<Long>,
        fatalIds: List<Long>,
        parseFailedIds: List<Long>,
        claimToken: String
    ) {
        val now = LocalDateTime.now()
        if (successIds.isNotEmpty()) {
            outboxRepository.bulkMarkPublished(successIds, claimToken, now)
        }
        if (retryableIds.isNotEmpty()) {
            outboxRepository.bulkReturnToInit(retryableIds, claimToken, now)
            log.warn("Kafka 발행 실패. 재시도 예정. ids={}", retryableIds)
        }
        // retry 소진 + payload 파싱 실패: retryCount++ (마지막 시도 횟수 기록)
        val exhaustedAndParseFailedIds = exhaustedIds + parseFailedIds
        if (exhaustedAndParseFailedIds.isNotEmpty()) {
            outboxRepository.bulkMarkFailed(exhaustedAndParseFailedIds, claimToken, now)
        }
        // FATAL: 직렬화 불가 등 재시도 무의미 → retryCount 유지 (시도 자체가 없었음)
        if (fatalIds.isNotEmpty()) {
            outboxRepository.bulkMarkFatalFailed(fatalIds, claimToken, now)
        }
    }
}

/**
 * Stuck Outbox 복구 스케줄러.
 *
 * 서버 비정상 종료로 PUBLISHING 상태에서 멈춘 row를 복구한다.
 * recovery도 "실패 1회"로 간주하여 retry_count++.
 * - retry budget 남음 → INIT (재처리 대상)
 * - retry budget 소진 → FAILED (무한 복구 루프 방지)
 */
@Component
class OutboxRecoveryScheduler(
    private val outboxRepository: CouponOutboxRepository,
    @Value("\${coupon.outbox.recovery.stuck-threshold-minutes}") private val stuckThresholdMinutes: Long,
    @Value("\${coupon.outbox.relay.max-retry}") private val maxRetry: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${coupon.outbox.recovery.fixed-delay}")
    @Transactional
    fun recover() {
        val now = LocalDateTime.now()
        val stuckBefore = now.minusMinutes(stuckThresholdMinutes)
        outboxRepository.recoverStuckToInit(now, stuckBefore, maxRetry)
        outboxRepository.failStuckExhausted(now, stuckBefore, maxRetry)
        log.debug("Stuck outbox 복구 완료. stuckBefore={}, maxRetry={}", stuckBefore, maxRetry)
    }
}

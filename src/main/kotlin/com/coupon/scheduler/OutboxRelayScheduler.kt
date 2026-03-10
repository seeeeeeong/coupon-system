package com.coupon.scheduler

import com.coupon.domain.CouponOutbox
import com.coupon.domain.OutboxStatus
import com.coupon.kafka.CouponEventProducer
import com.coupon.kafka.CouponIssueEvent
import com.coupon.repository.CouponOutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Outbox Relay Scheduler.
 *
 * 트랜잭션 분리 전략 (DB 커넥션 고갈 방지):
 *   트랜잭션1: SELECT FOR UPDATE SKIP LOCKED → 행 수집 → 즉시 커밋 (lock 해제)
 *   네트워크 I/O: Kafka 발행 시도 (트랜잭션 밖)
 *   트랜잭션2: 결과에 따라 status UPDATE
 *
 * Kafka I/O 도중 DB 트랜잭션 커넥션을 점유하면 커넥션 풀이 고갈될 수 있다.
 * lock을 빠르게 해제해야 다른 인스턴스가 나머지 배치를 처리할 수 있다.
 *
 * Fixed Delay 선택 이유:
 * - Fixed Rate은 이전 작업 지연 시 스레드 꼬임 위험
 * - Fixed Delay는 이전 작업 완료 후 1초 대기 → 자연스러운 Back-pressure
 */
@Component
class OutboxRelayScheduler(
    private val outboxUpdateService: OutboxUpdateService,
    private val producer: CouponEventProducer,
    @Value("\${coupon.outbox.relay.batch-size}") private val batchSize: Int,
    @Value("\${coupon.outbox.relay.max-retry}") private val maxRetry: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().apply { registerModule(JavaTimeModule()) }

    @Scheduled(fixedDelayString = "\${coupon.outbox.scheduler.fixed-delay}")
    fun relay() {
        // 트랜잭션1: SELECT FOR UPDATE SKIP LOCKED → 즉시 커밋 (lock 해제)
        val batch = outboxUpdateService.fetchBatch(batchSize, maxRetry)
        if (batch.isEmpty()) return

        log.debug("Outbox relay 시작. batch size={}", batch.size)

        // 트랜잭션 밖에서 Kafka I/O 수행
        val successIds = mutableListOf<Long>()
        val failedIds = mutableListOf<Long>()

        batch.forEach { outbox ->
            val event = parseEvent(outbox)
            if (event != null && producer.publish(event)) {
                successIds.add(outbox.id)
            } else {
                failedIds.add(outbox.id)
            }
        }

        // 트랜잭션2: 결과에 따라 status UPDATE
        if (successIds.isNotEmpty()) {
            outboxUpdateService.markPublished(successIds)
        }
        if (failedIds.isNotEmpty()) {
            outboxUpdateService.incrementRetry(failedIds, maxRetry)
            log.warn("Kafka 발행 실패. 재시도 예정. failedIds={}", failedIds)
        }

        log.debug("Outbox relay 완료. success={}, failed={}", successIds.size, failedIds.size)
    }

    private fun parseEvent(outbox: CouponOutbox): CouponIssueEvent? {
        return try {
            objectMapper.readValue(outbox.payload, CouponIssueEvent::class.java)
        } catch (e: Exception) {
            log.error("Outbox payload 파싱 실패. id={}, payload={}", outbox.id, outbox.payload, e)
            null
        }
    }
}

/**
 * Relay 결과 상태 업데이트를 별도 빈으로 분리.
 * 자기 자신을 호출하면 @Transactional이 동작하지 않으므로(self-invocation 문제) 별도 컴포넌트로 분리.
 */
@Component
class OutboxUpdateService(
    private val outboxRepository: CouponOutboxRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 트랜잭션1: SKIP LOCKED로 배치 조회 후 즉시 커밋.
     */
    @Transactional
    fun fetchBatch(batchSize: Int, maxRetry: Int): List<CouponOutbox> {
        return outboxRepository.findBatchForRelay(OutboxStatus.INIT.name, maxRetry, batchSize)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPublished(ids: List<Long>) {
        val outboxes = outboxRepository.findAllById(ids)
        outboxes.forEach { it.markPublished() }
        outboxRepository.saveAll(outboxes)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementRetry(ids: List<Long>, maxRetry: Int) {
        val outboxes = outboxRepository.findAllById(ids)
        outboxes.forEach { outbox ->
            outbox.incrementRetry(maxRetry)
            if (outbox.status == OutboxStatus.FAILED) {
                log.error(
                    "[ALERT] Outbox 최대 재시도 초과. 수동 처리 필요. outboxId={}, payload={}",
                    outbox.id, outbox.payload
                )
                // 실제 운영: Slack/PagerDuty 알림 연동 지점
                // alertService.sendFailedOutboxAlert(outbox)
            }
        }
        outboxRepository.saveAll(outboxes)
    }
}

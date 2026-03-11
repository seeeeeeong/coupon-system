package com.coupon.kafka

import org.apache.kafka.common.errors.InvalidTopicException
import org.apache.kafka.common.errors.SerializationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
class CouponEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    @Value("\${coupon.kafka.topic}") private val topic: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 비동기 Kafka 발행. 즉시 반환하고 CompletableFuture가 delivery.timeout.ms 내에 완료될 가능성을 높인다.
     *
     * 호출부(OutboxRelayScheduler)는 배치 전체의 Future를 모아 한 번에 수집함으로써
     * 100개 순차 대기(sum of RTT) → 병렬 대기(max of RTT)로 전환한다.
     *
     * Key는 userId로 설정하여 파티션 분산.
     * couponTemplateId를 Key로 쓰면 단일 파티션 집중(Hot Partition) 문제 발생.
     */
    fun sendAsync(event: CouponIssueEvent): CompletableFuture<ProduceResult> {
        return kafkaTemplate.send(topic, event.userId.toString(), event)
            .handle { _, ex ->
                if (ex == null) {
                    log.debug("Kafka 발행 성공. eventId={}, userId={}", event.eventId, event.userId)
                    ProduceResult.SUCCESS
                } else {
                    classify(ex.cause ?: ex, event)
                }
            }
    }

    private fun classify(ex: Throwable, event: CouponIssueEvent): ProduceResult {
        return when (ex) {
            is SerializationException,
            is InvalidTopicException -> {
                log.error(
                    "Kafka 발행 영구 실패(FATAL). eventId={}, userId={}, error={}",
                    event.eventId, event.userId, ex.message
                )
                ProduceResult.FATAL
            }
            else -> {
                log.warn(
                    "Kafka 발행 실패(RETRYABLE_UNKNOWN). eventId={}, userId={}, error={}",
                    event.eventId, event.userId, ex.message
                )
                ProduceResult.RETRYABLE_UNKNOWN
            }
        }
    }
}

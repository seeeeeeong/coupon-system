package com.coupon.kafka

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class CouponEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, CouponIssueEvent>,
    @Value("\${coupon.kafka.topic}") private val topic: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Kafka에 쿠폰 발급 이벤트를 발행한다.
     * Key는 userId로 설정하여 파티션 분산 효과를 얻는다.
     * (couponTemplateId를 Key로 쓰면 단일 파티션에 몰리는 Hot Partition 문제 발생)
     *
     * @return true: 발행 성공, false: 발행 실패
     */
    fun publish(event: CouponIssueEvent): Boolean {
        return try {
            kafkaTemplate.send(topic, event.userId.toString(), event).get()
            log.debug("Kafka 발행 성공. eventId={}, userId={}", event.eventId, event.userId)
            true
        } catch (e: Exception) {
            log.warn("Kafka 발행 실패. eventId={}, userId={}, error={}", event.eventId, event.userId, e.message)
            false
        }
    }
}

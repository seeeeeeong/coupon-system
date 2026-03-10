package com.coupon.kafka

import com.coupon.domain.Coupon
import com.coupon.repository.CouponRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Kafka Consumer.
 *
 * enable.auto.commit = false → 수동 커밋
 * coupons 테이블 insert 성공 또는 unique violation(중복) 시에만 offset commit.
 * 그 외 예외는 offset 미커밋 → 재시도.
 *
 * unique violation을 정상 처리로 간주하는 이유:
 * - 에러로 처리하면 Consumer가 같은 메시지를 무한 재시도 → Consumer Group 마비
 * - 이 메시지는 이미 처리된 중복이므로 정상 완료 처리 후 다음 메시지로 넘어간다
 */
@Component
class CouponEventConsumer(
    private val couponRepository: CouponRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${coupon.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        concurrency = "3"
    )
    fun consume(record: ConsumerRecord<String, CouponIssueEvent>, ack: Acknowledgment) {
        val event = record.value()
        log.debug(
            "쿠폰 발급 이벤트 소비. eventId={}, userId={}, couponTemplateId={}",
            event.eventId, event.userId, event.couponTemplateId
        )

        try {
            couponRepository.save(
                Coupon(
                    userId = event.userId,
                    couponTemplateId = event.couponTemplateId,
                    issuedAt = LocalDateTime.now()
                )
            )
            log.info("쿠폰 발급 완료. userId={}, couponTemplateId={}", event.userId, event.couponTemplateId)
            ack.acknowledge()

        } catch (e: DataIntegrityViolationException) {
            // unique violation: 이미 발급된 쿠폰 → 정상 완료 처리 후 offset commit
            log.warn(
                "중복 쿠폰 발급 시도 감지. 정상 처리로 간주. userId={}, couponTemplateId={}",
                event.userId, event.couponTemplateId
            )
            ack.acknowledge()

        } catch (e: Exception) {
            // DB 장애 등 재시도 가능한 오류 → offset 미커밋, 재시도
            log.error(
                "쿠폰 발급 실패. 재시도 대기. eventId={}, userId={}, error={}",
                event.eventId, event.userId, e.message
            )
            // ack 호출하지 않음 → Kafka가 재전달
        }
    }
}

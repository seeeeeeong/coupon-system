package com.coupon.kafka

import com.coupon.domain.Coupon
import com.coupon.repository.CouponRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Kafka Consumer.
 *
 * 에러 처리 전략 (KafkaConfig.kafkaListenerContainerFactory 참고):
 *   역직렬화 실패  → listener 진입 전 container에서 감지 → 즉시 DLT
 *   uq_user_coupon unique violation → 정상 처리로 간주하여 ack (멱등성 보장)
 *   그 외 DataIntegrityViolationException → 예외 rethrow → DefaultErrorHandler backoff + DLT
 *   DB 장애 등 RuntimeException → 예외 rethrow → DefaultErrorHandler backoff + DLT
 *
 * unique violation을 정상 처리로 간주하는 이유:
 *   Outbox at-least-once 구조에서 중복 발행은 설계상 발생 가능하다.
 *   unique key(uq_user_coupon)가 최종 방어선이며, 중복을 오류로 처리하면
 *   같은 메시지를 무한 재시도하여 Consumer Group이 마비된다.
 *
 * uq_user_coupon 이외의 DataIntegrityViolationException을 구분하는 이유:
 *   제약 조건 추가 시 다른 위반이 "중복으로 정상 처리"되어 데이터가 유실될 수 있다.
 *   constraint name으로 범위를 좁혀 의도하지 않은 ack를 방지한다.
 */
@Component
class CouponEventConsumer(
    private val couponRepository: CouponRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${coupon.kafka.topic}"],
        groupId = "\${spring.kafka.consumer.group-id}",
        concurrency = "\${coupon.kafka.listener.concurrency}"
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
            if (isUniqueViolation(e)) {
                // uq_user_coupon: 이미 발급된 쿠폰 → 정상 완료 처리 후 offset commit
                log.warn(
                    "중복 쿠폰 발급 시도 감지. 정상 처리로 간주. userId={}, couponTemplateId={}",
                    event.userId, event.couponTemplateId
                )
                ack.acknowledge()
            } else {
                // uq_user_coupon 이외의 제약 위반 → DefaultErrorHandler에 위임 (backoff + DLT)
                throw e
            }
        }
        // DB 장애 등 그 외 예외는 catch하지 않음 → DefaultErrorHandler가 backoff + 재시도 → DLT
    }

    /**
     * Hibernate ConstraintViolationException의 constraintName으로 uq_user_coupon 여부를 판별.
     * message 파싱 방식은 DB/버전에 따라 포맷이 달라질 수 있으므로 constraintName을 직접 사용한다.
     */
    private fun isUniqueViolation(e: DataIntegrityViolationException): Boolean {
        val cause = e.cause
        return cause is HibernateConstraintViolationException &&
               cause.constraintName?.equals("uq_user_coupon", ignoreCase = true) == true
    }
}

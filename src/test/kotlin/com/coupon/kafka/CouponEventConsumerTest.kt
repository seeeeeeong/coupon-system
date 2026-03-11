package com.coupon.kafka

import com.coupon.domain.Coupon
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.support.Acknowledgment
import org.springframework.test.context.ActiveProfiles
import java.sql.SQLIntegrityConstraintViolationException
import java.time.LocalDateTime

/**
 * CouponEventConsumer 통합 테스트.
 *
 * consume() 직접 호출로 비즈니스 로직을 검증한다.
 * Kafka 브로커 불필요 (application-test.yml: auto-startup=false).
 * CouponRepository: MockBean (예외 시나리오 주입 가능).
 *
 * 검증 시나리오:
 *  1. 정상 발급 → save 호출, ack.acknowledge() 호출
 *  2. uq_user_coupon unique violation → ack.acknowledge() 호출 (멱등성)
 *  3. uq_user_coupon 이외의 DataIntegrityViolationException → 예외 rethrow, ack 미호출
 *  4. DB 장애 등 RuntimeException → 예외 rethrow, ack 미호출
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponEventConsumerTest {

    @Autowired
    lateinit var consumer: CouponEventConsumer

    @MockBean
    lateinit var couponRepository: CouponRepository

    @MockBean
    lateinit var couponRedisService: CouponRedisService

    // ─────────────────────────────────────────────
    // 1. 정상 발급
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("정상 발급 → save 호출, ack.acknowledge() 호출")
    fun `정상 발급`() {
        val event = couponIssueEvent()
        val ack = mock<Acknowledgment>()
        whenever(couponRepository.save(any<Coupon>())).thenReturn(Coupon(userId = 1L, couponTemplateId = 1L))

        consumer.consume(record(event), ack)

        verify(couponRepository).save(any<Coupon>())
        verify(ack).acknowledge()
    }

    // ─────────────────────────────────────────────
    // 2. uq_user_coupon unique violation → ack (멱등성)
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("uq_user_coupon unique violation → ack 호출, 예외 미전파")
    fun `uq_user_coupon 중복 정상 처리`() {
        val event = couponIssueEvent()
        val ack = mock<Acknowledgment>()
        whenever(couponRepository.save(any<Coupon>())).thenThrow(uniqueViolationException("uq_user_coupon"))

        consumer.consume(record(event), ack)

        verify(ack).acknowledge()
    }

    // ─────────────────────────────────────────────
    // 3. uq_user_coupon 이외의 DataIntegrityViolationException → rethrow
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("uq_user_coupon 이외의 제약 위반 → 예외 rethrow, ack 미호출")
    fun `다른 제약 위반 예외 rethrow`() {
        val event = couponIssueEvent()
        val ack = mock<Acknowledgment>()
        whenever(couponRepository.save(any<Coupon>())).thenThrow(uniqueViolationException("some_other_constraint"))

        assertThrows<DataIntegrityViolationException> {
            consumer.consume(record(event), ack)
        }

        verify(ack, never()).acknowledge()
    }

    // ─────────────────────────────────────────────
    // 4. DB 장애 등 RuntimeException → rethrow
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("DB 장애(RuntimeException) → 예외 rethrow, ack 미호출")
    fun `DB 장애 예외 rethrow`() {
        val event = couponIssueEvent()
        val ack = mock<Acknowledgment>()
        whenever(couponRepository.save(any<Coupon>())).thenThrow(RuntimeException("Connection timeout"))

        assertThrows<RuntimeException> {
            consumer.consume(record(event), ack)
        }

        verify(ack, never()).acknowledge()
    }

    // ─────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────
    private fun couponIssueEvent() = CouponIssueEvent(
        eventId = 1L,
        userId = 1L,
        couponTemplateId = 1L,
        requestedAt = LocalDateTime.now()
    )

    private fun record(event: CouponIssueEvent) =
        ConsumerRecord<String, CouponIssueEvent>("coupon.issue.request", 0, 0L, "1", event)

    /**
     * Hibernate가 MySQL unique violation 발생 시 생성하는 예외 체인을 재현한다.
     * DataIntegrityViolationException → HibernateConstraintViolationException → SQLIntegrityConstraintViolationException
     */
    private fun uniqueViolationException(constraintName: String): DataIntegrityViolationException {
        val sqlEx = SQLIntegrityConstraintViolationException("Duplicate entry for key '$constraintName'")
        val hibernateCause = HibernateConstraintViolationException(
            "could not execute statement", sqlEx, constraintName
        )
        return DataIntegrityViolationException("could not execute statement", hibernateCause)
    }
}

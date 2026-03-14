package com.coupon.config

import com.coupon.redis.CouponRedisService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.test.context.ActiveProfiles

/**
 * KafkaConfig 설정 검증 테스트.
 *
 * DefaultErrorHandler를 별도 빈으로 분리했으므로 직접 주입하여 설정을 검증한다.
 *
 * container-level(ErrorHandlingDeserializer → DefaultErrorHandler → DLT) end-to-end 검증은
 * @EmbeddedKafka 통합 테스트로 별도 작업이 필요하다.
 * 이 테스트는 그 전까지 설정 오류를 조기에 잡는 역할을 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class KafkaErrorHandlerConfigTest {

    @Autowired
    lateinit var kafkaErrorHandler: DefaultErrorHandler

    @MockBean
    lateinit var couponRedisService: CouponRedisService

    @Value("\${coupon.kafka.listener.concurrency}")
    private var concurrency: Int = 0

    // ─────────────────────────────────────────────
    // 1. DefaultErrorHandler 빈 등록 여부
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("kafkaErrorHandler는 DefaultErrorHandler 빈으로 등록된다")
    fun `DefaultErrorHandler 빈 등록 검증`() {
        assertThat(kafkaErrorHandler).isNotNull()
        assertThat(kafkaErrorHandler).isInstanceOf(DefaultErrorHandler::class.java)
    }

    // ─────────────────────────────────────────────
    // 2. concurrency 설정값 유효성
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("coupon.kafka.listener.concurrency 설정값이 양수다")
    fun `concurrency 설정 검증`() {
        assertThat(concurrency).isPositive()
    }
}

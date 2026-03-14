package com.coupon.kafka

import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponRepository
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Poison message → DLT end-to-end 통합 테스트.
 *
 * ErrorHandlingDeserializer + DefaultErrorHandler(non-retryable) + DelegatingByTypeSerializer
 * 전체 경로가 실제로 동작하는지 검증한다.
 *
 * 시나리오:
 *   malformed JSON bytes를 ByteArraySerializer로 직접 발행 (JsonSerializer 우회)
 *   → ErrorHandlingDeserializer가 역직렬화 실패 감지, 헤더에 원본 bytes + 예외 정보 저장
 *   → DefaultErrorHandler가 DeserializationException을 non-retryable로 분류 → 즉시 DLT
 *   → DeadLetterPublishingRecoverer가 원본 bytes를 DLT 토픽으로 전송
 *   → DelegatingByTypeSerializer가 byte[]를 ByteArraySerializer로 직렬화 → 원본 보존
 *   → DLT consumer가 수신한 value == 원본 bytes
 *
 * @DirtiesContext: 테스트 완료 후 Kafka listener container 포함 전체 컨텍스트를 초기화하여
 *   다른 테스트에 영향을 주지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 3,
    topics = ["coupon.issue.request", "coupon.issue.request.DLT"],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = ["spring.kafka.listener.auto-startup=true"])
@DirtiesContext
class PoisonMessageDltTest {

    @Autowired
    lateinit var embeddedKafka: EmbeddedKafkaBroker

    @MockBean
    lateinit var couponRedisService: CouponRedisService

    @MockBean
    lateinit var couponRepository: CouponRepository

    @Test
    @DisplayName("역직렬화 실패(poison message)는 retry 없이 DLT로 전달되며 원본 bytes가 보존된다")
    fun `poison message는 DLT로 전달된다`() {
        val poisonBytes = "{invalid-json{{{".toByteArray()
        val topic = "coupon.issue.request"
        val dltTopic = "coupon.issue.request.DLT"

        // 1. malformed bytes를 ByteArraySerializer로 직접 발행 (JsonSerializer 우회)
        //    CouponEventConsumer의 ErrorHandlingDeserializer가 역직렬화 실패를 감지한다.
        val producerProps = KafkaTestUtils.producerProps(embeddedKafka)
        producerProps[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java.name
        producerProps[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java.name
        val rawProducer = KafkaProducer<String, ByteArray>(producerProps)
        rawProducer.send(ProducerRecord(topic, "test-key", poisonBytes)).get(5, TimeUnit.SECONDS)
        rawProducer.close()

        // 2. DLT 토픽에서 레코드 수신
        //    DeserializationException은 non-retryable → backoff 없이 즉시 DLT 전송.
        //    DelegatingByTypeSerializer가 byte[]를 ByteArraySerializer로 직렬화하므로
        //    DLT 레코드의 value는 원본 bytes 그대로다.
        val consumerProps = KafkaTestUtils.consumerProps("test-dlt-group", "true", embeddedKafka)
        consumerProps[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
        consumerProps[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java.name
        val dltConsumer = KafkaConsumer<String, ByteArray>(consumerProps)
        dltConsumer.subscribe(listOf(dltTopic))

        val records = KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(30))
        dltConsumer.close()

        // 3. DLT 도착 + 원본 bytes 보존 검증
        assertThat(records.count()).isGreaterThan(0)
        val dltRecord = records.records(dltTopic).first()
        assertThat(dltRecord.value()).isEqualTo(poisonBytes)

        // 4. 역직렬화 실패 → consumer 비즈니스 로직 미실행 검증
        //    ErrorHandlingDeserializer가 예외를 가로채 헤더에 담으므로
        //    CouponEventConsumer.consume()이 호출되지 않아야 한다.
        verifyNoInteractions(couponRepository)
    }
}

package com.coupon.config

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConfig(
    @Value("\${coupon.kafka.topic}") private val topic: String,
    @Value("\${coupon.kafka.partitions}") private val partitions: Int,
    // replication-factor=1: 로컬/단일 브로커 개발 환경 전용.
    // 운영 환경에서는 최소 3으로 설정해야 브로커 1대 장애 시 토픽 가용성이 유지된다.
    // acks=all과 replication-factor=1 조합은 브로커가 1대뿐이면 기능상 acks=1과 동일하다.
    @Value("\${coupon.kafka.replication-factor}") private val replicationFactor: Int
) {
    /**
     * 토픽 자동 생성. 파티션 수는 Consumer 최대 병렬도 상한이다.
     */
    @Bean
    fun couponIssueTopic(): NewTopic {
        return TopicBuilder.name(topic)
            .partitions(partitions)
            .replicas(replicationFactor)
            .build()
    }

    /**
     * DLT(Dead Letter Topic) 자동 생성.
     * retry 소진 후 처리 불가 레코드가 이 토픽으로 전달된다.
     * 운영자가 DLT를 모니터링하여 수동 재처리 또는 원인 분석에 활용한다.
     */
    @Bean
    fun couponIssueDltTopic(): NewTopic {
        return TopicBuilder.name("$topic.DLT")
            .partitions(partitions)
            .replicas(replicationFactor)
            .build()
    }

    /**
     * Spring Boot auto-configuration의 ProducerFactory를 그대로 활용.
     * application.yml의 spring.kafka.producer 설정이 ProducerFactory에 반영된다.
     * KafkaTemplate<String, Any>: 이벤트 타입 확장 시에도 유연하게 대응.
     */
    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
        return KafkaTemplate(producerFactory)
    }

    /**
     * KafkaListenerContainerFactory 커스터마이징.
     *
     * configurer.configure(): spring.kafka.listener.* 설정(ack-mode, auto-startup 등)을
     * 모두 factory에 적용한다. 직접 설정하면 application.yml과 이중 관리가 발생하므로
     * configurer에 위임하여 단일 진실의 원천을 유지한다.
     *
     * 에러 처리 전략:
     *   DeserializationException → non-retryable, 즉시 DLT 전송 (재시도해도 동일 오류 반복)
     *   그 외 예외(DB 장애 등) → FixedBackOff(1s, 3회 재시도) 후 DLT 전송
     *
     * DLT 전용 KafkaTemplate (DelegatingByTypeSerializer):
     *   역직렬화 실패 레코드의 value는 byte[]로 전달된다.
     *   기존 JsonSerializer로 byte[]를 직렬화하면 숫자 배열 JSON이 되어 원본이 손실된다.
     *   byte[]  → ByteArraySerializer (원본 바이트 보존)
     *   그 외   → JsonSerializer     (retry 소진된 정상 이벤트)
     *
     * DLT: DeadLetterPublishingRecoverer가 {topic}.DLT 토픽으로 원본 레코드를 전송.
     * 운영자가 DLT 레코드를 확인 후 수동 재처리 또는 원인 제거 후 재발행한다.
     * 실제 운영: Slack/PagerDuty 알림 + DLT Consumer 연동 지점.
     */
    /**
     * DefaultErrorHandler를 별도 빈으로 분리.
     * kafkaListenerContainerFactory에 주입하고 테스트에서도 직접 검증 가능하다.
     */
    @Bean
    fun kafkaErrorHandler(producerFactory: ProducerFactory<String, Any>): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(dltKafkaTemplate(producerFactory))
        // 1s 간격, 최대 3회 재시도. 소진 후 DLT 전송.
        val backOff = FixedBackOff(1_000L, 3L)
        val errorHandler = DefaultErrorHandler(recoverer, backOff)
        // 역직렬화 실패는 재시도해도 동일 오류 → non-retryable로 분류, 즉시 DLT
        errorHandler.addNotRetryableExceptions(DeserializationException::class.java)
        return errorHandler
    }

    @Bean
    fun kafkaListenerContainerFactory(
        configurer: ConcurrentKafkaListenerContainerFactoryConfigurer,
        consumerFactory: ConsumerFactory<Any, Any>,
        kafkaErrorHandler: DefaultErrorHandler,
        @Value("\${coupon.kafka.listener.concurrency}") concurrency: Int
    ): ConcurrentKafkaListenerContainerFactory<Any, Any> {
        val factory = ConcurrentKafkaListenerContainerFactory<Any, Any>()
        // spring.kafka.listener.* 전체(ack-mode, auto-startup 등)를 factory에 위임 적용
        configurer.configure(factory, consumerFactory)

        factory.setCommonErrorHandler(kafkaErrorHandler)
        factory.setConcurrency(concurrency)

        return factory
    }

    /**
     * DLT 발행 전용 KafkaTemplate.
     *
     * DelegatingByTypeSerializer(assignable=true)로 타입별 직렬화를 분기한다.
     *   byte[]  → ByteArraySerializer: 역직렬화 실패 시 ErrorHandlingDeserializer가 전달하는
     *             원본 바이트를 그대로 DLT에 보존. JsonSerializer로 직렬화하면 숫자 배열이 되어 복구 불가.
     *   그 외   → JsonSerializer: retry 소진된 이벤트(CouponIssueEvent 등)는 JSON으로 저장.
     *
     * assignable=true: exact match 우선, 없으면 isAssignableFrom으로 탐색.
     *   → Object(Any)가 모든 타입의 상위이므로 fallback 역할.
     */
    @Suppress("UNCHECKED_CAST")
    private fun dltKafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
        val delegates = mapOf<Class<*>, Serializer<*>>(
            ByteArray::class.java to ByteArraySerializer(),
            Any::class.java to JsonSerializer<Any>()
        )
        val dltSerializer = DelegatingByTypeSerializer(delegates, true) as Serializer<Any>
        return KafkaTemplate(
            DefaultKafkaProducerFactory(
                producerFactory.configurationProperties,
                StringSerializer(),
                dltSerializer
            )
        )
    }
}

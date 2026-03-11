package com.coupon.config

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

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
     * Spring Boot auto-configuration의 ProducerFactory를 그대로 활용.
     * application.yml의 spring.kafka.producer 설정이 ProducerFactory에 반영된다.
     * KafkaTemplate<String, Any>: 이벤트 타입 확장 시에도 유연하게 대응.
     */
    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> {
        return KafkaTemplate(producerFactory)
    }
}

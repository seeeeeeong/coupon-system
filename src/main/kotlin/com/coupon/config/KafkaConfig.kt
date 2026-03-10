package com.coupon.config

import com.coupon.kafka.CouponIssueEvent
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
    @Value("\${coupon.kafka.replication-factor}") private val replicationFactor: Int
) {

    /**
     * 토픽 자동 생성.
     * 파티션 수는 운영 파라미터로 application.yml에서 조정 가능.
     * 파티션 수가 Consumer 수의 상한이다.
     */
    @Bean
    fun couponIssueTopic(): NewTopic {
        return TopicBuilder.name(topic)
            .partitions(partitions)
            .replicas(replicationFactor)
            .build()
    }

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, CouponIssueEvent>): KafkaTemplate<String, CouponIssueEvent> {
        return KafkaTemplate(producerFactory)
    }
}

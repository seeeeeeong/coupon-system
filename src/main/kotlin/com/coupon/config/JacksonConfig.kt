package com.coupon.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {

    /**
     * 공용 ObjectMapper 빈.
     * - KotlinModule: Kotlin data class 역직렬화 지원 (no-arg constructor 없이도 동작)
     * - JavaTimeModule: LocalDateTime 직렬화 지원
     * - WRITE_DATES_AS_TIMESTAMPS 비활성화: LocalDateTime을 ISO-8601 문자열로 직렬화.
     *   비활성화하지 않으면 [2026,3,15,12,0,0] 형태로 직렬화되어 HTTP 응답,
     *   Outbox payload, Redis 캐시 직렬화 전반에서 가독성과 호환성이 깨진다.
     * CouponIssueService, OutboxRelayScheduler 등이 공유한다.
     */
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}

package com.coupon.config

import com.fasterxml.jackson.databind.ObjectMapper
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
     * CouponIssueService, OutboxRelayScheduler 등이 공유한다.
     */
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }
}

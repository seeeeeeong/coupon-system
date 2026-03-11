package com.coupon.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer
import java.time.Duration

@Configuration
@EnableCaching
class RedisConfig {

    @Bean
    fun stringRedisTemplate(connectionFactory: RedisConnectionFactory): StringRedisTemplate {
        return StringRedisTemplate(connectionFactory)
    }

    /**
     * CouponTemplate 캐시 설정.
     * TTL 10분: 이벤트 기간 동안 템플릿 정보 변경이 거의 없으므로 캐시 효율이 높다.
     * 10만 트래픽에서 getTemplateById()가 캐시 miss 폭탄이 되면 DB가 먼저 죽으므로
     * 역직렬화 정확성이 핵심이다.
     *
     * ObjectMapper 설계:
     * - JacksonConfig 공용 빈을 copy()하여 KotlinModule이 포함된 상태에서 시작.
     *   기존 코드처럼 로컬로 ObjectMapper를 새로 만들면 KotlinModule이 빠져
     *   Kotlin data class 역직렬화 실패 → 캐시 miss 반복 → DB 병목.
     * - activateDefaultTyping: 역직렬화 시 타입 복원을 위해 필요하지만 범위를 제한한다.
     *   NON_FINAL + 모든 클래스 허용(LaissezFaire)은 Jackson 역직렬화 공격 노출 위험.
     *   BasicPolymorphicTypeValidator로 com.coupon 패키지만 허용하여 공격 범위 최소화.
     */
    @Bean
    fun cacheManager(
        connectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper  // JacksonConfig 빈 주입 (KotlinModule + JavaTimeModule 포함)
    ): CacheManager {
        val cacheObjectMapper = objectMapper.copy().apply {
            activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfSubType("com.coupon")
                    .build(),
                ObjectMapper.DefaultTyping.NON_FINAL
            )
        }

        val defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    GenericJackson2JsonRedisSerializer(cacheObjectMapper)
                )
            )

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .build()
    }
}

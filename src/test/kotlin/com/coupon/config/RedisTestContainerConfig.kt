package com.coupon.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * Redis Testcontainer 설정.
 *
 * @ServiceConnection: 컨테이너가 뜨면 Spring이 자동으로 host/port를 주입한다.
 * application-test.yml의 redis host/port 설정을 오버라이드하므로
 * 로컬 Redis나 CI 환경 무관하게 테스트가 완전히 자기충족적으로 동작한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RedisTestContainerConfig {

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
}

package com.coupon.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class CouponRedisServiceTest {

    @Autowired
    lateinit var couponRedisService: CouponRedisService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private val couponTemplateId = 9999L

    @BeforeEach
    fun setUp() {
        // 각 테스트 전 Redis 키 초기화
        redisTemplate.delete(CouponRedisService.stockKey(couponTemplateId))
        redisTemplate.delete(CouponRedisService.issuedKey(couponTemplateId))
    }

    @Test
    @DisplayName("재고 초기화 후 Lua Script 실행 시 성공 반환")
    fun `정상 발급 성공`() {
        couponRedisService.initializeStock(couponTemplateId, 100, Duration.ofHours(1))

        val result = couponRedisService.tryIssue(couponTemplateId, 1L)

        assertThat(result).isEqualTo(CouponRedisService.RESULT_SUCCESS)
        assertThat(couponRedisService.getStock(couponTemplateId)).isEqualTo(99L)
    }

    @Test
    @DisplayName("동일 userId 재요청 시 중복 반환")
    fun `중복 요청 처리`() {
        couponRedisService.initializeStock(couponTemplateId, 100, Duration.ofHours(1))
        couponRedisService.tryIssue(couponTemplateId, 1L)

        val result = couponRedisService.tryIssue(couponTemplateId, 1L)

        assertThat(result).isEqualTo(CouponRedisService.RESULT_DUPLICATE)
    }

    @Test
    @DisplayName("재고 0 상태에서 요청 시 품절 반환")
    fun `품절 처리`() {
        couponRedisService.initializeStock(couponTemplateId, 1, Duration.ofHours(1))
        couponRedisService.tryIssue(couponTemplateId, 1L) // 마지막 재고 소진

        val result = couponRedisService.tryIssue(couponTemplateId, 2L)

        assertThat(result).isEqualTo(CouponRedisService.RESULT_SOLD_OUT)
    }

    @Test
    @DisplayName("100개 재고에 200명 동시 요청 시 초과 발급 없음")
    fun `동시성 테스트 - 초과 발급 없음`() {
        val totalStock = 100
        val totalRequests = 200
        couponRedisService.initializeStock(couponTemplateId, totalStock, Duration.ofHours(1))

        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)
        val latch = CountDownLatch(totalRequests)
        val executor = Executors.newFixedThreadPool(50)

        repeat(totalRequests) { i ->
            executor.submit {
                try {
                    when (couponRedisService.tryIssue(couponTemplateId, i.toLong() + 1)) {
                        CouponRedisService.RESULT_SUCCESS  -> successCount.incrementAndGet()
                        CouponRedisService.RESULT_SOLD_OUT -> soldOutCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertThat(successCount.get()).isEqualTo(totalStock)
        assertThat(soldOutCount.get()).isEqualTo(totalRequests - totalStock)
        assertThat(couponRedisService.getStock(couponTemplateId)).isEqualTo(0L)
    }
}

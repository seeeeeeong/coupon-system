package com.coupon.redis

import com.coupon.config.RedisTestContainerConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("test")
@Import(RedisTestContainerConfig::class)
class CouponRedisServiceTest {

    @Autowired
    lateinit var couponRedisService: CouponRedisService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private val couponTemplateId = 9999L
    private val expireAt = Instant.now().plus(2, ChronoUnit.HOURS)
    private val expireAtEpoch = expireAt.epochSecond

    @BeforeEach
    fun setUp() {
        redisTemplate.delete(CouponRedisService.stockKey(couponTemplateId))
        redisTemplate.delete(CouponRedisService.issuedKey(couponTemplateId))
    }

    @Test
    @DisplayName("재고 초기화 후 발급 성공")
    fun `정상 발급 성공`() {
        couponRedisService.initializeStock(couponTemplateId, 100, expireAt)

        val result = couponRedisService.tryIssue(couponTemplateId, 1L, expireAtEpoch)

        assertThat(result).isEqualTo(LuaResult.SUCCESS)
        assertThat(couponRedisService.getStock(couponTemplateId)).isEqualTo(99L)
    }

    @Test
    @DisplayName("동일 userId 재요청 시 DUPLICATE 반환")
    fun `중복 요청 처리`() {
        couponRedisService.initializeStock(couponTemplateId, 100, expireAt)
        couponRedisService.tryIssue(couponTemplateId, 1L, expireAtEpoch)

        val result = couponRedisService.tryIssue(couponTemplateId, 1L, expireAtEpoch)

        assertThat(result).isEqualTo(LuaResult.DUPLICATE)
    }

    @Test
    @DisplayName("재고 소진 후 새 userId 요청 시 SOLD_OUT 반환")
    fun `품절 처리`() {
        couponRedisService.initializeStock(couponTemplateId, 1, expireAt)
        couponRedisService.tryIssue(couponTemplateId, 1L, expireAtEpoch)

        val result = couponRedisService.tryIssue(couponTemplateId, 2L, expireAtEpoch)

        assertThat(result).isEqualTo(LuaResult.SOLD_OUT)
    }

    @Test
    @DisplayName("stock 키 미초기화 상태에서 요청 시 STATE_MISSING 반환 (품절로 오인하지 않음)")
    fun `미초기화 상태 분리`() {
        val result = couponRedisService.tryIssue(couponTemplateId, 1L, expireAtEpoch)

        assertThat(result).isEqualTo(LuaResult.STATE_MISSING)
    }

    @Test
    @DisplayName("발급 성공 후 issued Set에 TTL이 설정됨")
    fun `issued Set TTL 유지`() {
        couponRedisService.initializeStock(couponTemplateId, 100, expireAt)
        couponRedisService.tryIssue(couponTemplateId, 1L, expireAtEpoch)

        val ttl = redisTemplate.getExpire(CouponRedisService.issuedKey(couponTemplateId))

        // TTL -1이면 만료 설정 없는 영구 키 → 버그
        assertThat(ttl).isGreaterThan(0)
    }

    @Test
    @DisplayName("키에 해시태그 {coupon:id}가 포함되어 Redis Cluster 호환")
    fun `해시태그 키 포맷 검증`() {
        val stockKey = CouponRedisService.stockKey(couponTemplateId)
        val issuedKey = CouponRedisService.issuedKey(couponTemplateId)

        assertThat(stockKey).contains("{coupon:$couponTemplateId}")
        assertThat(issuedKey).contains("{coupon:$couponTemplateId}")
    }

    @Test
    @DisplayName("100개 재고에 200명 동시 요청 시 초과 발급 없음")
    fun `동시성 테스트 - 초과 발급 없음`() {
        val totalStock = 100
        val totalRequests = 200
        couponRedisService.initializeStock(couponTemplateId, totalStock, expireAt)

        val successCount = AtomicInteger(0)
        val soldOutCount = AtomicInteger(0)
        val latch = CountDownLatch(totalRequests)
        val executor = Executors.newFixedThreadPool(50)

        repeat(totalRequests) { i ->
            executor.submit {
                try {
                    when (couponRedisService.tryIssue(couponTemplateId, i.toLong() + 1, expireAtEpoch)) {
                        LuaResult.SUCCESS  -> successCount.incrementAndGet()
                        LuaResult.SOLD_OUT -> soldOutCount.incrementAndGet()
                        else               -> {}
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

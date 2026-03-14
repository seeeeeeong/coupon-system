package com.coupon.service

import com.coupon.domain.CouponTemplate
import com.coupon.exception.CouponAlreadyIssuedException
import com.coupon.exception.CouponSoldOutException
import com.coupon.exception.CouponStateInvalidException
import com.coupon.exception.EventExpiredException
import com.coupon.exception.EventNotStartedException
import com.coupon.redis.CouponRedisService
import com.coupon.redis.LuaResult
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

@SpringBootTest
@ActiveProfiles("test")
class CouponIssueServiceTest {

    @Autowired
    lateinit var couponIssueService: CouponIssueService

    @MockBean
    lateinit var couponTemplateService: CouponTemplateService

    @MockBean
    lateinit var couponRedisService: CouponRedisService

    @MockBean
    lateinit var couponOutboxService: CouponOutboxService

    private fun makeTemplate(
        startAt: LocalDateTime = LocalDateTime.now().minusHours(1),
        endAt: LocalDateTime = LocalDateTime.now().plusHours(1)
    ) = CouponTemplate(
        id = 1L,
        name = "테스트 쿠폰",
        discountAmount = 5000,
        totalQuantity = 100,
        eventStartAt = startAt,
        eventEndAt = endAt
    )

    // ─────────────────────────────────────────────
    // 1. 정상 발급
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Redis SUCCESS → couponOutboxService.save() 호출, 롤백 미호출")
    fun `정상 발급`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(eq(1L), eq(1L), any())).thenReturn(LuaResult.SUCCESS)

        couponIssueService.issue(1L, 1L)

        verify(couponOutboxService).save(1L, 1L)
        verify(couponRedisService, never()).rollbackIssue(any(), any())
    }

    // ─────────────────────────────────────────────
    // 2. Outbox 저장 실패 → Redis 롤백 후 예외 전파
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Outbox 저장 실패 → Redis rollbackIssue 호출 후 예외 전파")
    fun `Outbox 저장 실패 시 Redis 롤백`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(eq(1L), eq(1L), any())).thenReturn(LuaResult.SUCCESS)
        whenever(couponOutboxService.save(1L, 1L)).thenThrow(RuntimeException("DB 장애"))

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("DB 장애")

        verify(couponRedisService).rollbackIssue(1L, 1L)
    }

    // ─────────────────────────────────────────────
    // 3. 이벤트 미시작
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("이벤트 미시작 시 EventNotStartedException")
    fun `이벤트 미시작`() {
        val template = makeTemplate(
            startAt = LocalDateTime.now().plusHours(1),
            endAt = LocalDateTime.now().plusHours(3)
        )
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        whenever(couponTemplateService.validateEvent(template))
            .thenThrow(EventNotStartedException(1L))

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(EventNotStartedException::class.java)
    }

    // ─────────────────────────────────────────────
    // 4. 이벤트 만료
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("이벤트 만료 시 EventExpiredException")
    fun `이벤트 만료`() {
        val template = makeTemplate(
            startAt = LocalDateTime.now().minusHours(3),
            endAt = LocalDateTime.now().minusHours(1)
        )
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        whenever(couponTemplateService.validateEvent(template))
            .thenThrow(EventExpiredException(1L))

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(EventExpiredException::class.java)
    }

    // ─────────────────────────────────────────────
    // 5. Redis 중복 결과
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Redis 중복 결과 시 CouponAlreadyIssuedException")
    fun `중복 요청`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(eq(1L), eq(1L), any())).thenReturn(LuaResult.DUPLICATE)

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(CouponAlreadyIssuedException::class.java)
    }

    // ─────────────────────────────────────────────
    // 6. Redis 품절
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Redis 품절 결과 시 CouponSoldOutException")
    fun `품절 처리`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(eq(1L), eq(1L), any())).thenReturn(LuaResult.SOLD_OUT)

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(CouponSoldOutException::class.java)
    }

    // ─────────────────────────────────────────────
    // 7. Redis STATE_MISSING
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Redis STATE_MISSING 결과 시 CouponStateInvalidException")
    fun `상태 이상`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(eq(1L), eq(1L), any())).thenReturn(LuaResult.STATE_MISSING)

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(CouponStateInvalidException::class.java)
    }
}

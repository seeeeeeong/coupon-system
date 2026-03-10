package com.coupon.service

import com.coupon.domain.CouponTemplate
import com.coupon.exception.CouponAlreadyIssuedException
import com.coupon.exception.CouponSoldOutException
import com.coupon.exception.EventExpiredException
import com.coupon.exception.EventNotStartedException
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponOutboxRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import java.time.LocalDateTime

@SpringBootTest
class CouponIssueServiceTest {

    @Autowired
    lateinit var couponIssueService: CouponIssueService

    @MockBean
    lateinit var couponTemplateService: CouponTemplateService

    @MockBean
    lateinit var couponRedisService: CouponRedisService

    @MockBean
    lateinit var couponOutboxRepository: CouponOutboxRepository

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

    @Test
    @DisplayName("Redis 중복 결과 시 CouponAlreadyIssuedException")
    fun `중복 요청`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(1L, 1L)).thenReturn(CouponRedisService.RESULT_DUPLICATE)

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(CouponAlreadyIssuedException::class.java)
    }

    @Test
    @DisplayName("Redis 품절 결과 시 CouponSoldOutException")
    fun `품절 처리`() {
        val template = makeTemplate()
        whenever(couponTemplateService.getTemplateById(1L)).thenReturn(template)
        doNothing().whenever(couponTemplateService).validateEvent(template)
        whenever(couponRedisService.tryIssue(1L, 1L)).thenReturn(CouponRedisService.RESULT_SOLD_OUT)

        assertThatThrownBy { couponIssueService.issue(1L, 1L) }
            .isInstanceOf(CouponSoldOutException::class.java)
    }
}

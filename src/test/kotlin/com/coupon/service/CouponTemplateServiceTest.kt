package com.coupon.service

import com.coupon.controller.dto.CouponTemplateCreateRequest
import com.coupon.exception.InvalidEventPeriodException
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponTemplateRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.LocalDateTime

/**
 * CouponTemplateService 단위 테스트.
 *
 * 서비스 레벨 교차 필드 검증(eventStartAt < eventEndAt)을 검증한다.
 * Spring 컨텍스트 없이 실행하여 테스트 속도를 최적화한다.
 */
class CouponTemplateServiceTest {

    private val couponTemplateRepository: CouponTemplateRepository = mock()
    private val couponRedisService: CouponRedisService = mock()
    private val service = CouponTemplateService(couponTemplateRepository, couponRedisService)

    // ─────────────────────────────────────────────
    // 1. 종료 시각 < 시작 시각 → InvalidEventPeriodException
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("eventEndAt < eventStartAt → InvalidEventPeriodException")
    fun `종료 시각이 시작 시각보다 빠르면 예외`() {
        val request = CouponTemplateCreateRequest(
            name = "테스트 쿠폰",
            discountAmount = 1000,
            totalQuantity = 100,
            eventStartAt = LocalDateTime.of(2026, 4, 1, 12, 0),
            eventEndAt = LocalDateTime.of(2026, 4, 1, 10, 0)  // 시작보다 2시간 앞
        )

        assertThatThrownBy { service.createTemplate(request) }
            .isInstanceOf(InvalidEventPeriodException::class.java)
    }

    // ─────────────────────────────────────────────
    // 2. 종료 시각 == 시작 시각 → InvalidEventPeriodException
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("eventEndAt == eventStartAt → InvalidEventPeriodException")
    fun `종료 시각과 시작 시각이 같으면 예외`() {
        val sameTime = LocalDateTime.of(2026, 4, 1, 10, 0)
        val request = CouponTemplateCreateRequest(
            name = "테스트 쿠폰",
            discountAmount = 1000,
            totalQuantity = 100,
            eventStartAt = sameTime,
            eventEndAt = sameTime
        )

        assertThatThrownBy { service.createTemplate(request) }
            .isInstanceOf(InvalidEventPeriodException::class.java)
    }
}

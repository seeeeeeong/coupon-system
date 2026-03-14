package com.coupon.controller

import com.coupon.exception.InvalidEventPeriodException
import com.coupon.service.CouponIssueService
import com.coupon.service.CouponTemplateService
import com.coupon.service.RedisInitService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath

/**
 * CouponController @Valid → GlobalExceptionHandler 통합 경로 검증.
 *
 * @WebMvcTest: Spring MVC 설정(DispatcherServlet, HandlerMapping, MessageConverter 등)과
 * @RestControllerAdvice 스캔 범위를 실제와 동일하게 구성하여 검증한다.
 * validation 의존성 누락이나 ExceptionHandler 연결 오류는 이 레벨에서만 탐지 가능하다.
 */
@WebMvcTest(CouponController::class)
class CouponControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var couponIssueService: CouponIssueService

    @MockBean
    lateinit var redisInitService: RedisInitService

    @MockBean
    lateinit var couponTemplateService: CouponTemplateService

    // ─────────────────────────────────────────────
    // 1. userId 검증 실패 → 400 INVALID_REQUEST
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("userId <= 0 → 400 INVALID_REQUEST")
    fun `userId 음수 요청은 400 반환`() {
        mockMvc.post("/api/v1/coupons/1/issue") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId": -1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    @DisplayName("userId = 0 → 400 INVALID_REQUEST")
    fun `userId 0 요청은 400 반환`() {
        mockMvc.post("/api/v1/coupons/1/issue") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"userId": 0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    // ─────────────────────────────────────────────
    // 2. 템플릿 생성 검증 실패 → 400 INVALID_REQUEST
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("totalQuantity <= 0 → 400 INVALID_REQUEST")
    fun `수량 0 이하 템플릿 생성은 400 반환`() {
        mockMvc.post("/api/v1/coupon-templates") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "name": "테스트",
                    "discountAmount": 1000,
                    "totalQuantity": 0,
                    "eventStartAt": "2026-04-01T10:00:00",
                    "eventEndAt": "2026-04-01T12:00:00"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    @DisplayName("name 빈 문자열 → 400 INVALID_REQUEST")
    fun `빈 이름으로 템플릿 생성은 400 반환`() {
        mockMvc.post("/api/v1/coupon-templates") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "name": "",
                    "discountAmount": 1000,
                    "totalQuantity": 100,
                    "eventStartAt": "2026-04-01T10:00:00",
                    "eventEndAt": "2026-04-01T12:00:00"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    // ─────────────────────────────────────────────
    // 3. 날짜 역전 → 400 INVALID_EVENT_PERIOD
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("eventEndAt < eventStartAt → 400 INVALID_EVENT_PERIOD")
    fun `종료 시각이 시작보다 빠른 템플릿 생성은 400 반환`() {
        whenever(couponTemplateService.createTemplate(any())).thenThrow(InvalidEventPeriodException())

        mockMvc.post("/api/v1/coupon-templates") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                    "name": "테스트",
                    "discountAmount": 1000,
                    "totalQuantity": 100,
                    "eventStartAt": "2026-04-01T12:00:00",
                    "eventEndAt": "2026-04-01T10:00:00"
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_EVENT_PERIOD") }
        }
    }
}

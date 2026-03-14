package com.coupon.controller

import com.coupon.controller.dto.CouponIssueRequest
import com.coupon.controller.dto.CouponTemplateCreateRequest
import com.coupon.controller.dto.StockResponse
import com.coupon.domain.CouponTemplate
import com.coupon.service.CouponIssueService
import com.coupon.service.CouponTemplateService
import com.coupon.service.RedisInitService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class CouponController(
    private val couponIssueService: CouponIssueService,
    private val redisInitService: RedisInitService,
    private val couponTemplateService: CouponTemplateService
) {

    /**
     * 쿠폰 발급 요청.
     * 202 Accepted: 요청이 접수됨. 실제 발급은 Kafka Consumer가 비동기 처리.
     * 클라이언트는 쿠폰함 폴링으로 최종 결과를 확인한다.
     */
    @PostMapping("/coupons/{couponTemplateId}/issue")
    fun issueCoupon(
        @PathVariable couponTemplateId: Long,
        @RequestBody @Valid request: CouponIssueRequest
    ): ResponseEntity<Map<String, String>> {
        couponIssueService.issue(couponTemplateId, request.userId)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(mapOf("message" to "쿠폰 발급 요청이 접수되었습니다. 쿠폰함을 확인해주세요."))
    }

    /**
     * 쿠폰 템플릿 생성 (테스트/관리용).
     */
    @PostMapping("/coupon-templates")
    fun createTemplate(
        @RequestBody @Valid request: CouponTemplateCreateRequest
    ): ResponseEntity<CouponTemplate> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(couponTemplateService.createTemplate(request))
    }

    /**
     * Redis 재고 초기화 (이벤트 시작 전에만 호출 가능).
     * 이벤트 시작 이후 호출하면 AlreadyInitializedException(409) 반환.
     * 시작 전에는 여러 번 호출 가능 (설정 변경 대응).
     */
    @PostMapping("/coupon-templates/{couponTemplateId}/init-stock")
    fun initStock(@PathVariable couponTemplateId: Long): ResponseEntity<Map<String, String>> {
        redisInitService.initializeCouponStock(couponTemplateId)
        return ResponseEntity.ok(mapOf("message" to "재고 초기화 완료"))
    }

    /**
     * 현재 Redis 잔여 재고 조회 (모니터링/정산용).
     */
    @GetMapping("/coupon-templates/{couponTemplateId}/stock")
    fun getStock(@PathVariable couponTemplateId: Long): ResponseEntity<StockResponse> {
        return ResponseEntity.ok(StockResponse(couponTemplateId, couponTemplateService.getStock(couponTemplateId)))
    }
}

package com.coupon.service

import com.coupon.domain.CouponTemplate
import com.coupon.exception.CouponTemplateNotFoundException
import com.coupon.exception.EventExpiredException
import com.coupon.exception.EventNotStartedException
import com.coupon.repository.CouponTemplateRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * CouponTemplate 조회 서비스.
 *
 * Spring Cache(@Cacheable)를 사용하여 Redis에 캐싱한다.
 * 이벤트 기간이 고정되므로 TTL 기반 캐시가 자연스럽다.
 * 5만 건 동시 요청 시 매 요청마다 DB를 조회하면 병목이 발생하므로
 * API 레벨 검증에 쓰이는 템플릿 조회는 반드시 캐시를 거쳐야 한다.
 */
@Service
class CouponTemplateService(
    private val couponTemplateRepository: CouponTemplateRepository
) {

    @Cacheable(cacheNames = ["couponTemplate"], key = "#id")
    fun getTemplateById(id: Long): CouponTemplate {
        return couponTemplateRepository.findById(id).orElseThrow {
            CouponTemplateNotFoundException(id)
        }
    }

    fun validateEvent(template: CouponTemplate) {
        val now = LocalDateTime.now()
        if (now.isBefore(template.eventStartAt)) {
            throw EventNotStartedException(template.id)
        }
        if (now.isAfter(template.eventEndAt)) {
            throw EventExpiredException(template.id)
        }
    }
}

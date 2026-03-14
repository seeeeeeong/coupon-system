package com.coupon.service

import com.coupon.controller.dto.CouponTemplateCreateRequest
import com.coupon.domain.CouponTemplate
import com.coupon.exception.CouponTemplateNotFoundException
import com.coupon.exception.EventExpiredException
import com.coupon.exception.EventNotStartedException
import com.coupon.exception.InvalidEventPeriodException
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponTemplateRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class CouponTemplateService(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponRedisService: CouponRedisService
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

    @Transactional
    fun createTemplate(request: CouponTemplateCreateRequest): CouponTemplate {
        if (!request.eventStartAt.isBefore(request.eventEndAt)) {
            throw InvalidEventPeriodException()
        }
        return couponTemplateRepository.save(
            CouponTemplate(
                name = request.name,
                discountAmount = request.discountAmount,
                totalQuantity = request.totalQuantity,
                eventStartAt = request.eventStartAt,
                eventEndAt = request.eventEndAt
            )
        )
    }

    fun getStock(couponTemplateId: Long): Long? = couponRedisService.getStock(couponTemplateId)
}

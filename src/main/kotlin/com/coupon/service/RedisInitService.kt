package com.coupon.service

import com.coupon.exception.CouponTemplateNotFoundException
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

@Service
class RedisInitService(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponRedisService: CouponRedisService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이벤트 시작 전 Redis 재고를 초기화한다.
     * TTL은 이벤트 종료 시각 기준 2배로 설정하여
     * 종료 후 정산 및 CS 대응 시간을 확보하면서 자동 만료시킨다.
     */
    fun initializeCouponStock(couponTemplateId: Long) {
        val template = couponTemplateRepository.findById(couponTemplateId).orElseThrow {
            CouponTemplateNotFoundException(couponTemplateId)
        }

        val eventDuration = Duration.between(template.eventStartAt, template.eventEndAt)
        val ttl = eventDuration.multipliedBy(2).plusHours(1).coerceAtLeast(Duration.ofHours(2))

        couponRedisService.initializeStock(couponTemplateId, template.totalQuantity, ttl)
        log.info(
            "쿠폰 재고 초기화 완료. couponTemplateId={}, quantity={}, ttl={}",
            couponTemplateId, template.totalQuantity, ttl
        )
    }
}

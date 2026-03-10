package com.coupon.service

import com.coupon.exception.AlreadyInitializedException
import com.coupon.exception.CouponTemplateNotFoundException
import com.coupon.redis.CouponRedisService
import com.coupon.repository.CouponTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class RedisInitService(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponRedisService: CouponRedisService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 이벤트 종료 후 정산 및 CS 대응을 위한 버퍼 (24시간)
        const val EXPIRE_BUFFER_HOURS = 24L
    }

    /**
     * 이벤트 시작 전 Redis 재고를 초기화한다.
     *
     * TTL 계산 기준: eventEndAt + buffer의 절대 시각 (EXPIREAT)
     * 상대 TTL(eventDuration * 2)을 쓰지 않는 이유:
     * - 상대 TTL은 초기화 시점 기준이라 "하루 전 초기화 + 1시간 이벤트"이면
     *   TTL이 3시간이 되어 이벤트 시작 전에 키가 만료될 수 있다.
     * - 절대 시각은 초기화 시점과 무관하게 항상 eventEndAt + buffer로 고정된다.
     *
     * 재초기화 보호:
     * - 이벤트 시작 이후에는 초기화 요청을 거부한다.
     * - 이벤트 중 재초기화는 기존 issued Set이 초기화되어 중복 발급이 열리고
     *   stock이 덮어써져 초과 발급이 가능해지는 운영 사고로 이어진다.
     * - "이미 발급 흔적이 있으면 금지"보다 "이벤트 시작 이후 금지"가
     *   더 단순하고 명확한 기준이다.
     */
    fun initializeCouponStock(couponTemplateId: Long) {
        val template = couponTemplateRepository.findById(couponTemplateId).orElseThrow {
            CouponTemplateNotFoundException(couponTemplateId)
        }

        val now = LocalDateTime.now()
        // !isBefore로 정각(eventStartAt == now)도 차단한다.
        // 이벤트 시작 전에는 여러 번 초기화를 허용한다.
        // 이벤트 시작 이후 재초기화는 issued Set을 초기화하여 중복 발급이 열리고
        // stock을 덮어써 초과 발급이 가능해지는 운영 사고로 이어진다.
        if (!now.isBefore(template.eventStartAt)) {
            throw AlreadyInitializedException(couponTemplateId)
        }

        val expireAt = template.eventEndAt
            .plusHours(EXPIRE_BUFFER_HOURS)
            .toInstant()

        couponRedisService.initializeStock(couponTemplateId, template.totalQuantity, expireAt)

        log.info(
            "쿠폰 재고 초기화 완료. couponTemplateId={}, quantity={}, expireAt={}",
            couponTemplateId, template.totalQuantity, expireAt
        )
    }

    private fun LocalDateTime.toInstant(): Instant =
        this.atZone(ZoneId.systemDefault()).toInstant()
}

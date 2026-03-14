package com.coupon.exception

class CouponTemplateNotFoundException(id: Long) :
    RuntimeException("쿠폰 템플릿을 찾을 수 없습니다. id=$id")

class EventNotStartedException(id: Long) :
    RuntimeException("아직 시작되지 않은 이벤트입니다. id=$id")

class EventExpiredException(id: Long) :
    RuntimeException("종료된 이벤트입니다. id=$id")

class CouponAlreadyIssuedException(userId: Long, couponTemplateId: Long) :
    RuntimeException("이미 발급된 쿠폰입니다. userId=$userId, couponTemplateId=$couponTemplateId")

class CouponSoldOutException(couponTemplateId: Long) :
    RuntimeException("쿠폰이 모두 소진되었습니다. couponTemplateId=$couponTemplateId")

class RedisUnavailableException :
    RuntimeException("쿠폰 발급 서비스가 일시적으로 중단되었습니다. 잠시 후 다시 시도해주세요.")

class CouponStateInvalidException(couponTemplateId: Long) :
    RuntimeException("쿠폰 발급 상태가 정상이 아닙니다. 관리자에게 문의해주세요. couponTemplateId=$couponTemplateId")

class AlreadyInitializedException(couponTemplateId: Long) :
    RuntimeException("이미 초기화된 이벤트입니다. 이벤트 시작 이후에는 재초기화할 수 없습니다. couponTemplateId=$couponTemplateId")

class InvalidEventPeriodException :
    RuntimeException("이벤트 종료 시각은 시작 시각보다 이후여야 합니다.")

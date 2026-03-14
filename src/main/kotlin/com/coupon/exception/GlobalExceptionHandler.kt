package com.coupon.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class ErrorResponse(val code: String, val message: String)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(CouponTemplateNotFoundException::class)
    fun handle(e: CouponTemplateNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("COUPON_TEMPLATE_NOT_FOUND", e.message!!))

    @ExceptionHandler(EventNotStartedException::class)
    fun handle(e: EventNotStartedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("EVENT_NOT_STARTED", e.message!!))

    @ExceptionHandler(EventExpiredException::class)
    fun handle(e: EventExpiredException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("EVENT_EXPIRED", e.message!!))

    @ExceptionHandler(CouponAlreadyIssuedException::class)
    fun handle(e: CouponAlreadyIssuedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_ALREADY_ISSUED", e.message!!))

    @ExceptionHandler(CouponSoldOutException::class)
    fun handle(e: CouponSoldOutException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("COUPON_SOLD_OUT", e.message!!))

    @ExceptionHandler(RedisUnavailableException::class)
    fun handle(e: RedisUnavailableException): ResponseEntity<ErrorResponse> {
        log.error("Redis 서비스 불가: ${e.message}")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("SERVICE_UNAVAILABLE", e.message!!))
    }

    @ExceptionHandler(CouponStateInvalidException::class)
    fun handle(e: CouponStateInvalidException): ResponseEntity<ErrorResponse> {
        log.error("쿠폰 상태 이상 감지 (미초기화 / TTL 만료 / 키 유실): ${e.message}")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("COUPON_STATE_INVALID", e.message!!))
    }

    @ExceptionHandler(AlreadyInitializedException::class)
    fun handle(e: AlreadyInitializedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("ALREADY_INITIALIZED", e.message!!))

    @ExceptionHandler(InvalidEventPeriodException::class)
    fun handle(e: InvalidEventPeriodException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("INVALID_EVENT_PERIOD", e.message!!))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handle(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("INVALID_REQUEST", message))
    }

    @ExceptionHandler(Exception::class)
    fun handle(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("예상치 못한 오류 발생", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."))
    }
}

package com.coupon.redis

/**
 * Lua Script 반환값을 타입 안전하게 표현하는 enum.
 *
 * Long 상수 비교 대신 enum을 쓰는 이유:
 * - when expression에서 exhaustive 강제 → 컴파일 타임에 누락된 케이스 감지
 * - 비즈니스 상태(SOLD_OUT)와 시스템 상태(STATE_MISSING)를 명확히 분리
 * - 새 반환값 추가 시 모든 처리 지점을 컴파일러가 알려줌
 */
enum class LuaResult(val code: Long) {
    SUCCESS(1),
    DUPLICATE(-1),
    SOLD_OUT(-2),
    STATE_MISSING(-3);   // stock == nil: 미초기화 / TTL 만료 / 운영 중 키 유실

    companion object {
        fun from(code: Long): LuaResult =
            entries.find { it.code == code }
                ?: throw IllegalStateException("Lua Script에서 알 수 없는 반환값을 받았습니다. code=$code")
    }
}

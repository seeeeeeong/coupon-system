package com.coupon.kafka

/**
 * Kafka 발행 결과 3분류.
 *
 * 단순 Boolean 대신 원인을 구분하여 Outbox 상태 전이 및 관찰성(observability) 확보.
 *
 * at-least-once 보장 구조에서 RETRYABLE_UNKNOWN도 retry 경로를 거치지만,
 * 로그/지표에서 "미확정(timeout)"과 "실패"를 구분해야
 * 피크 시 downstream 중복 압력의 원인을 추적할 수 있다.
 */
enum class ProduceResult {

    /** 발행 성공. broker ack 수신 확인. */
    SUCCESS,

    /**
     * 결과 미확정. timeout, 일시적 broker/네트워크 오류.
     *
     * broker에 실제로 기록됐을 수 있으므로 retry 시 중복 발행 가능.
     * 최종 방어는 Consumer 레벨 idempotent 처리.
     * 10만 피크에서 broker 응답 지연 → timeout 급증 → retry 급증의 경로를
     * 이 분류로 모니터링에서 추적한다.
     */
    RETRYABLE_UNKNOWN,

    /**
     * 재시도해도 동일하게 실패할 영구 오류.
     * 직렬화 불가, 존재하지 않는 토픽 등.
     * Outbox FAILED 처리 대상.
     */
    FATAL
}

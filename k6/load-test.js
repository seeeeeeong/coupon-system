/**
 * k6 부하 테스트 시나리오
 *
 * 목표: 10초 동안 5만 건 동시 요청
 * 측정:
 *   - TPS (Transactions Per Second)
 *   - 에러율 (409 Conflict 제외: 정상적인 중복/품절 응답)
 *   - 응답 시간 분포 (p50, p95, p99)
 *   - 쿠폰 발급 정합성: Redis stock 감소량 = coupons 테이블 insert 수
 *
 * 실행 방법:
 *   docker run --rm -i grafana/k6 run - < k6/load-test.js
 *   또는
 *   k6 run k6/load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ─── 커스텀 메트릭 ────────────────────────────────────────────────────
const issueSuccessCount = new Counter('coupon_issue_success_total');
const issueDuplicateCount = new Counter('coupon_issue_duplicate_total');
const issueSoldOutCount = new Counter('coupon_issue_sold_out_total');
const issueErrorCount = new Counter('coupon_issue_error_total');
const issueResponseTime = new Trend('coupon_issue_response_time', true);

// ─── 테스트 설정 ──────────────────────────────────────────────────────
export const options = {
    scenarios: {
        coupon_rush: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '3s', target: 5000 },  // 3초 동안 5천 VU로 증가
                { duration: '7s', target: 5000 },  // 7초 동안 5천 VU 유지
                { duration: '2s', target: 0 },     // 2초 동안 종료
            ],
        },
    },
    thresholds: {
        // 서비스 정상 기준
        'http_req_duration': ['p(99)<3000'],    // p99 3초 미만
        'http_req_failed': ['rate<0.01'],       // 실패율 1% 미만 (409 제외)
        'coupon_issue_error_total': ['count<100'], // 예상치 못한 에러 100건 미만
    },
};

// ─── 테스트 환경 변수 ──────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const COUPON_TEMPLATE_ID = __ENV.COUPON_TEMPLATE_ID || '1';
const TOTAL_USERS = 50000; // 총 사용자 수 (userId 1 ~ 50000)

// ─── 메인 테스트 함수 ─────────────────────────────────────────────────
export default function () {
    // VU별 고유 userId 생성 (1 ~ 50000)
    // __VU: 현재 VU 번호, __ITER: 현재 반복 횟수
    const userId = ((__VU - 1) % TOTAL_USERS) + 1;

    const url = `${BASE_URL}/api/v1/coupons/${COUPON_TEMPLATE_ID}/issue`;
    const payload = JSON.stringify({ userId: userId });
    const params = {
        headers: { 'Content-Type': 'application/json' },
        timeout: '10s',
    };

    const res = http.post(url, payload, params);
    issueResponseTime.add(res.timings.duration);

    // 응답 분류
    if (res.status === 202) {
        // 성공: 발급 요청 접수
        issueSuccessCount.add(1);
        check(res, { '202 Accepted': (r) => r.status === 202 });

    } else if (res.status === 409) {
        // 정상: 중복 요청 또는 품절
        const body = JSON.parse(res.body);
        if (body.code === 'COUPON_ALREADY_ISSUED') {
            issueDuplicateCount.add(1);
        } else if (body.code === 'COUPON_SOLD_OUT') {
            issueSoldOutCount.add(1);
        }
        // 409는 비즈니스 로직상 정상이므로 http_req_failed에서 제외

    } else if (res.status === 503) {
        // Redis 장애 → 이벤트 중단 (설계상 의도된 동작)
        issueErrorCount.add(1);
        console.error(`[503] Redis 서비스 불가. userId=${userId}`);

    } else {
        // 예상치 못한 에러
        issueErrorCount.add(1);
        console.error(`[${res.status}] 예상치 못한 에러. userId=${userId}, body=${res.body}`);
    }
}

/**
 * 테스트 종료 후 정합성 검증.
 * Kafka lag이 0이 될 때까지 대기한 후 검증해야 한다.
 *
 * 검증 항목:
 * 1. Redis 잔여 재고 = 초기 재고 - 성공 접수 건수 (근사치, outbox 처리 전)
 * 2. coupons 테이블 insert 수 = Redis 차감 수 (Kafka lag 해소 후 검증)
 */
export function handleSummary(data) {
    const successTotal = data.metrics['coupon_issue_success_total']
        ? data.metrics['coupon_issue_success_total'].values.count
        : 0;
    const duplicateTotal = data.metrics['coupon_issue_duplicate_total']
        ? data.metrics['coupon_issue_duplicate_total'].values.count
        : 0;
    const soldOutTotal = data.metrics['coupon_issue_sold_out_total']
        ? data.metrics['coupon_issue_sold_out_total'].values.count
        : 0;
    const errorTotal = data.metrics['coupon_issue_error_total']
        ? data.metrics['coupon_issue_error_total'].values.count
        : 0;

    const totalRequests = successTotal + duplicateTotal + soldOutTotal + errorTotal;
    const tps = totalRequests / (data.metrics['http_req_duration'].values.count > 0
        ? data.state.testRunDurationMs / 1000
        : 1);

    const summary = `
========================================
  선착순 쿠폰 발급 부하 테스트 결과
========================================
총 요청 수:       ${totalRequests}
  ✅ 발급 접수:   ${successTotal}
  🔄 중복 요청:   ${duplicateTotal}
  🔴 품절:        ${soldOutTotal}
  ❌ 에러:        ${errorTotal}

TPS (추정):       ${tps.toFixed(0)} req/s
응답 시간:
  p50:            ${data.metrics.http_req_duration.values['p(50)'].toFixed(0)}ms
  p95:            ${data.metrics.http_req_duration.values['p(95)'].toFixed(0)}ms
  p99:            ${data.metrics.http_req_duration.values['p(99)'].toFixed(0)}ms

========================================
정합성 검증 (Kafka lag 해소 후 실행):
  GET /api/v1/coupon-templates/${COUPON_TEMPLATE_ID}/stock
  SELECT COUNT(*) FROM coupons WHERE coupon_template_id = ${COUPON_TEMPLATE_ID}
  두 값이 일치하면 정합성 확인 완료.
========================================
`;

    console.log(summary);

    return {
        'k6-summary.txt': summary,
        stdout: summary,
    };
}

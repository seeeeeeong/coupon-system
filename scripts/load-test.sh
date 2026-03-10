#!/bin/bash
# k6 부하 테스트 실행 스크립트
# 실행 전: 쿠폰 템플릿 생성 및 Redis 재고 초기화가 완료되어 있어야 한다.

set -e

BASE_URL=${BASE_URL:-"http://localhost:8080"}
COUPON_TEMPLATE_ID=${COUPON_TEMPLATE_ID:-"1"}

echo "====================================="
echo "  부하 테스트 전 준비"
echo "====================================="

# 1. 쿠폰 템플릿 생성
echo "[1/3] 쿠폰 템플릿 생성..."
TEMPLATE_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/v1/coupon-templates" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "선착순 5천원 할인 쿠폰",
    "discountAmount": 5000,
    "totalQuantity": 1000,
    "eventStartAt": "'"$(date -u +"%Y-%m-%dT%H:%M:%S")"'",
    "eventEndAt": "'"$(date -u -d '+1 hour' +"%Y-%m-%dT%H:%M:%S" 2>/dev/null || date -u -v+1H +"%Y-%m-%dT%H:%M:%S")"'"
  }')
COUPON_TEMPLATE_ID=$(echo $TEMPLATE_RESPONSE | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "1")
echo "  쿠폰 템플릿 생성 완료. id=$COUPON_TEMPLATE_ID"

# 2. Redis 재고 초기화
echo "[2/3] Redis 재고 초기화..."
curl -s -X POST "${BASE_URL}/api/v1/coupon-templates/${COUPON_TEMPLATE_ID}/init-stock" | python3 -m json.tool
echo "  Redis 초기화 완료."

# 3. k6 부하 테스트 실행
echo "[3/3] k6 부하 테스트 시작..."
echo "  대상: ${BASE_URL}"
echo "  쿠폰 템플릿: ${COUPON_TEMPLATE_ID}"
echo ""

if command -v k6 &> /dev/null; then
    k6 run \
        -e BASE_URL="${BASE_URL}" \
        -e COUPON_TEMPLATE_ID="${COUPON_TEMPLATE_ID}" \
        k6/load-test.js
else
    echo "k6가 설치되어 있지 않습니다. Docker로 실행합니다..."
    docker run --rm -i \
        -e BASE_URL="${BASE_URL}" \
        -e COUPON_TEMPLATE_ID="${COUPON_TEMPLATE_ID}" \
        --network host \
        grafana/k6 run - < k6/load-test.js
fi

echo ""
echo "====================================="
echo "  정합성 검증"
echo "====================================="
echo "Kafka lag 해소 대기 (30초)..."
sleep 30

echo "Redis 잔여 재고:"
curl -s "${BASE_URL}/api/v1/coupon-templates/${COUPON_TEMPLATE_ID}/stock" | python3 -m json.tool

echo ""
echo "DB 발급 건수 확인:"
docker compose exec -T mysql mysql -u coupon_user -pcoupon_pass coupon_db \
  -e "SELECT COUNT(*) as issued_count FROM coupons WHERE coupon_template_id = ${COUPON_TEMPLATE_ID};"

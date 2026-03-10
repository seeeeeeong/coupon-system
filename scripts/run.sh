#!/bin/bash
# 전체 환경 실행 스크립트

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "====================================="
echo "  선착순 쿠폰 발급 시스템 시작"
echo "====================================="

# 1. 인프라 시작
echo "[1/4] 인프라 컨테이너 시작 (MySQL, Redis, Kafka, Prometheus, Grafana)..."
docker compose up -d mysql redis kafka prometheus grafana

# 2. 헬스체크 대기
echo "[2/4] 서비스 준비 대기 중..."
echo "  MySQL 대기..."
until docker compose exec -T mysql mysqladmin ping -h localhost -u root -proot_pass --silent 2>/dev/null; do
    sleep 2
done

echo "  Redis 대기..."
until docker compose exec -T redis redis-cli ping | grep -q PONG 2>/dev/null; do
    sleep 1
done

echo "  Kafka 대기..."
until docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list &>/dev/null; do
    sleep 2
done

echo "[3/4] 모든 인프라 준비 완료."

# 3. 애플리케이션 빌드 및 실행
echo "[4/4] Spring Boot 애플리케이션 빌드 및 실행..."
./gradlew bootRun &

echo ""
echo "====================================="
echo "  서비스 접속 정보"
echo "====================================="
echo "  API:        http://localhost:8080"
echo "  Grafana:    http://localhost:3000  (admin/admin)"
echo "  Prometheus: http://localhost:9090"
echo "====================================="

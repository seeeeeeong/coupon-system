#!/bin/bash
# 전체 환경 실행 스크립트

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

# 1. Infra Start
echo "[1/4] Infra Start (MySQL, Redis, Kafka, Prometheus, Grafana)..."
docker compose up -d mysql redis kafka prometheus grafana

# 2. wait health check
echo "[2/4] wait health check..."
echo "  wait mysql..."
until docker compose exec -T mysql mysqladmin ping -h localhost -u root -proot_pass --silent 2>/dev/null; do
    sleep 2
done

echo "  wait redis..."
until docker compose exec -T redis redis-cli ping | grep -q PONG 2>/dev/null; do
    sleep 1
done

echo "  wait kafka..."
until docker compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list &>/dev/null; do
    sleep 2
done

echo "[3/4] infra end"

# 3. app build
echo "[4/4] run springboot..."
./gradlew bootRun &

echo ""
echo "====================================="
echo "  info"
echo "====================================="
echo "  API:        http://localhost:8080"
echo "  Grafana:    http://localhost:3000  (admin/admin)"
echo "  Prometheus: http://localhost:9090"
echo "====================================="

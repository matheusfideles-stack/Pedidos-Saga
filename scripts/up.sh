#!/usr/bin/env bash
# Um comando: sobe TODA a stack (docker compose), espera ficar saudavel, executa o smoke test ponta a ponta
# e roda os testes de integracao (Testcontainers).
#   ./scripts/up.sh                 # 1 replica de cada servico
#   REPLICAS=3 ./scripts/up.sh      # 3 replicas de cada servico
#   SKIP_IT=1 ./scripts/up.sh       # sem os testes de integracao (so sobe + smoke)
set -euo pipefail
cd "$(dirname "$0")/.."

REPLICAS="${REPLICAS:-1}"
export ORDER_REPLICAS="$REPLICAS" INVENTORY_REPLICAS="$REPLICAS" PAYMENT_REPLICAS="$REPLICAS"

echo ">> docker compose up (replicas=$REPLICAS)"
docker compose up -d --build

echo ">> aguardando o gateway (http://localhost:8080)"
for _ in $(seq 1 120); do
  curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null 2>&1 && break
  sleep 3
done
curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null

echo ">> aguardando o registro dos conectores Debezium"
for _ in $(seq 1 60); do
  state=$(docker compose ps -a --format '{{.State}} {{.ExitCode}}' connector-init 2>/dev/null | head -1)
  [ "$state" = "exited 0" ] && break
  sleep 3
done
docker compose ps -a connector-init
curl -fsS http://localhost:8084/connectors; echo

echo ">> smoke test ponta a ponta"
BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh

if [ "${SKIP_IT:-0}" != "1" ]; then
  echo ">> testes de integracao (Testcontainers)"
  mvn -B -ntp verify -pl integration-tests -am
fi
echo ">> pronto. API: http://localhost:8080  |  Kafka Connect: http://localhost:8084  |  analytics: http://localhost:9100/metrics"

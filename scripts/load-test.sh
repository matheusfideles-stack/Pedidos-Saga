#!/usr/bin/env bash
# Teste de carga ANTES/DEPOIS de escalar, contra o local-stack (sem Docker).
#   ./scripts/load-test.sh <label> <replicas> <rate/s> <duracao>      ex.: ./scripts/load-test.sh 3-replicas 3 60 60s
# Sobe um stack limpo com N replicas de cada servico, aquece, roda o k6 (modelo aberto: pedidos/s fixos),
# e depois mede quanto a saga leva para DRENAR (todos os pedidos aceitos chegarem a CONFIRMED/CANCELLED).
# Resultado: load-test/results/<label>.json (k6) e <label>-drain.json (saga).  Requer k6 (var K6 ou PATH).
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:?label}"; REPLICAS="${2:?replicas}"; RATE="${3:?rate}"; DURATION="${4:?duracao}"
K6="${K6:-k6}"
OUT="local-stack/target/stack.properties"

./scripts/local-stack.sh stop >/dev/null 2>&1 || true
sleep 3
./scripts/local-stack.sh start "$REPLICAS" "--gateway-latency-ms=${GATEWAY_LATENCY_MS:-30}"

BASES=""; for i in $(seq 0 $((REPLICAS-1))); do BASES="${BASES:+$BASES,}http://localhost:$((8081 + i*10))"; done

completed() { # soma das transicoes terminais (CONFIRMED + CANCELLED) em todas as replicas do order-service
  local total=0
  for i in $(seq 0 $((REPLICAS-1))); do
    local c
    c=$(curl -s "http://localhost:$((8081 + i*10))/actuator/prometheus" \
      | grep '^orders_state_transitions_total' | grep -E 'to="(CONFIRMED|CANCELLED)"' \
      | awk '{s+=$2} END {printf "%d", s}')
    total=$((total + ${c:-0}))
  done
  echo "$total"
}

echo ">> aquecimento (JIT, pools, cache)"
IFS=',' read -ra B <<< "$BASES"
for n in $(seq 1 40); do
  curl -s -o /dev/null -X POST "${B[$((n % REPLICAS))]}/orders" -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"warm-$n\",\"items\":[{\"productId\":\"P-006\",\"quantity\":1}]}"
done
sleep 15
BASE_DONE=$(completed)

echo ">> k6: ${RATE} pedidos/s por ${DURATION} em ${REPLICAS} replica(s)"
mkdir -p load-test/results
START=$(date +%s)
"$K6" run -e BASE_URLS="$BASES" -e RATE="$RATE" -e DURATION="$DURATION" -e POLL_EVERY=20 -e LABEL="$LABEL" \
  load-test/orders.js > "target/k6-$LABEL.log" 2>&1 || echo "(k6 terminou com thresholds violados; resultados gravados mesmo assim)"
K6_END=$(date +%s)

ACCEPTED=$(grep -o '"iterations": [0-9]*' "load-test/results/$LABEL.json" | grep -o '[0-9]*')
DURATION_S=$(( ${DURATION%s} ))
echo ">> drenando a saga ($ACCEPTED iteracoes)"
DEADLINE=$(( $(date +%s) + 600 ))
while :; do
  DONE=$(( $(completed) - BASE_DONE ))
  # 'iterations' conta todas as iteracoes (inclui as que falharam); o criterio real e: nada mais mudando
  if [ "$DONE" -ge "$ACCEPTED" ]; then break; fi
  PREV=$DONE; sleep 5; NOW=$(( $(completed) - BASE_DONE ))
  if [ "$NOW" -eq "$PREV" ] && [ "$NOW" -gt 0 ]; then DONE=$NOW; break; fi
  [ "$(date +%s)" -gt "$DEADLINE" ] && { echo "timeout"; break; }
done
END=$(date +%s)
cat > "load-test/results/$LABEL-drain.json" <<EOF
{
  "label": "$LABEL",
  "replicas": $REPLICAS,
  "orders_created_by_k6": $ACCEPTED,
  "orders_completed": $DONE,
  "load_seconds": $((K6_END - START)),
  "seconds_until_saga_drained": $((END - START)),
  "saga_completion_rate_per_s": $(awk "BEGIN {printf \"%.1f\", $DONE / ($END - $START)}")
}
EOF
cat "load-test/results/$LABEL-drain.json"
./scripts/local-stack.sh stop >/dev/null 2>&1 || true

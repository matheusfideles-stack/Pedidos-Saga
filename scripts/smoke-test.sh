#!/usr/bin/env bash
# Smoke test HTTP ponta a ponta contra a stack em execucao (docker compose ou local-stack).
#   BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh
# Cobre: fluxo feliz, Idempotency-Key (replay e 422), falta de estoque, pagamento recusado e cancelamento.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
FAILS=0

json_field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
post() { curl -s -X POST "$BASE_URL$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }
status_of() { curl -s "$BASE_URL/orders/$1" | json_field status; }

wait_status() { # id esperado [tentativas]
  local id=$1 want=$2 tries=${3:-90} s
  for _ in $(seq 1 "$tries"); do
    s=$(status_of "$id"); [ "$s" = "$want" ] && return 0; sleep 1
  done
  echo "   (ultimo estado: $s)"; return 1
}

check() { # descricao comando...
  local desc=$1; shift
  if "$@"; then echo "  ok   $desc"; else echo "  FAIL $desc"; FAILS=$((FAILS+1)); fi
}

echo "== smoke test em $BASE_URL"

echo "-- 1. fluxo feliz"
R=$(post /orders '{"customerId":"smoke-happy","items":[{"productId":"P-001","quantity":2},{"productId":"P-002","quantity":1}]}')
ID=$(echo "$R" | json_field id)
check "pedido criado com estado PENDING" test "$(echo "$R" | json_field status)" = "PENDING"
check "saga termina em CONFIRMED" wait_status "$ID" CONFIRMED

echo "-- 2. Idempotency-Key"
KEY="smoke-$(date +%s)-$RANDOM"
P='{"customerId":"smoke-idem","items":[{"productId":"P-006","quantity":1}]}'
A=$(post /orders "$P" -H "Idempotency-Key: $KEY" | json_field id)
B=$(post /orders "$P" -H "Idempotency-Key: $KEY" | json_field id)
check "mesma chave + mesmo payload devolve o MESMO pedido" test -n "$A" -a "$A" = "$B"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/orders" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"customerId":"smoke-idem","items":[{"productId":"P-006","quantity":9}]}')
check "mesma chave + payload diferente retorna 422" test "$CODE" = "422"

echo "-- 3. falta de estoque (P-009 tem 5 unidades)"
ID=$(post /orders '{"customerId":"smoke-stock","items":[{"productId":"P-009","quantity":50}]}' | json_field id)
check "pedido CANCELLED por estoque" wait_status "$ID" CANCELLED

echo "-- 4. pagamento recusado (compensacao libera o estoque)"
ID=$(post /orders '{"customerId":"decline-smoke","items":[{"productId":"P-010","quantity":1}]}' | json_field id)
check "pedido CANCELLED por pagamento" wait_status "$ID" CANCELLED

echo "-- 5. cancelamento"
ID=$(post /orders '{"customerId":"smoke-conf","items":[{"productId":"P-007","quantity":1}]}' | json_field id)
wait_status "$ID" CONFIRMED >/dev/null
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/orders/$ID/cancel")
check "cancelar pedido CONFIRMED retorna 409" test "$CODE" = "409"

echo
if [ "$FAILS" -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE FALHOU ($FAILS)"; exit 1; fi

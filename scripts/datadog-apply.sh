#!/usr/bin/env bash
# Publica dashboard, SLO e monitores versionados em infra/datadog usando a API do Datadog.
# Uso: DD_API_KEY=... DD_APP_KEY=... [DD_SITE=datadoghq.com] ./scripts/datadog-apply.sh
set -euo pipefail

: "${DD_API_KEY:?defina DD_API_KEY}"
: "${DD_APP_KEY:?defina DD_APP_KEY}"
SITE="${DD_SITE:-datadoghq.com}"
DIR="$(cd "$(dirname "$0")/../infra/datadog" && pwd)"
API="https://api.${SITE}/api/v1"
H=(-H "DD-API-KEY: ${DD_API_KEY}" -H "DD-APPLICATION-KEY: ${DD_APP_KEY}" -H "Content-Type: application/json")

echo ">> dashboard"
curl -fsS -X POST "${API}/dashboard" "${H[@]}" -d @"${DIR}/dashboard.json" | sed 's/.*"url":"\([^"]*\)".*/   https:\/\/app.'"${SITE}"'\1/'

echo ">> SLO"
SLO_ID=$(curl -fsS -X POST "${API}/slo" "${H[@]}" -d @"${DIR}/slo.json" | sed 's/.*"id":"\([^"]*\)".*/\1/')
echo "   slo id: ${SLO_ID}"

echo ">> monitores"
COUNT=$(grep -c '"name"' "${DIR}/monitors.json" || true)
# monitores sao um array; envia um por vez (jq e opcional: sem ele, use a UI/Terraform)
if command -v jq >/dev/null 2>&1; then
  jq -c '.[]' "${DIR}/monitors.json" | while read -r monitor; do
    body=$(echo "${monitor}" | sed "s/<ID_DO_SLO_DISPONIBILIDADE_CRIACAO_PEDIDOS>/${SLO_ID}/")
    curl -fsS -X POST "${API}/monitor" "${H[@]}" -d "${body}" >/dev/null && echo "   ok: $(echo "${body}" | jq -r .name)"
  done
else
  echo "   jq nao encontrado: instale jq para criar os monitores (${COUNT} definicoes em monitors.json)"
fi

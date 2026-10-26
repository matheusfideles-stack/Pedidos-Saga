# Equivalente ao scripts/up.sh para Windows (sem make): sobe a stack, espera, smoke test e testes de integracao.
#   .\scripts\up.ps1 [-Replicas 3] [-SkipIT]
param([int]$Replicas = 1, [switch]$SkipIT)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$env:ORDER_REPLICAS = $Replicas; $env:INVENTORY_REPLICAS = $Replicas; $env:PAYMENT_REPLICAS = $Replicas

docker compose up -d --build
Write-Host ">> aguardando o gateway"
for ($i = 0; $i -lt 120; $i++) {
  try { Invoke-RestMethod http://localhost:8080/actuator/health/readiness | Out-Null; break } catch { Start-Sleep 3 }
}
Write-Host ">> smoke test (precisa de bash/curl: Git Bash ou WSL)"
$env:BASE_URL = "http://localhost:8080"
bash ./scripts/smoke-test.sh
if (-not $SkipIT) { mvn -B -ntp verify -pl integration-tests -am }

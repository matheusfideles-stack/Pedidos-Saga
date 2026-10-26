# ADR-006: Resiliência na chamada ao gateway de pagamento e reconciliação

- **Status**: aceita
- **Contexto**: a cobrança é a única chamada **síncrona de rede** do fluxo e a mais perigosa: pode falhar, demorar,
  ou cobrar e perder a resposta (resultado ambíguo).

## Decisão

Ordem das proteções (de fora para dentro): **Retry → CircuitBreaker → Bulkhead → TimeLimiter → HTTP** (Resilience4j,
composição programática em `ResilientPaymentGateway`).

| Proteção | Configuração | Motivo |
|---|---|---|
| Timeouts de rede | conexão 500ms, leitura 2s (`HttpPaymentGateway`) + TimeLimiter 3s | nenhuma chamada fica pendurada |
| Retry | 3 tentativas, backoff exponencial **com jitter** (`randomized-wait-factor: 0.5`), **somente** `TransientGatewayException`/timeout | erros 4xx/recusas **não** são repetidos (retry de erro permanente só adiciona carga) |
| Circuit breaker | janela de 20 chamadas, abre com ≥50% de falhas (mín. 10), 10s aberto, 3 chamadas de teste | falhar rápido e dar fôlego ao gateway; cada tentativa de retry passa pelo breaker |
| Bulkhead | 20 chamadas concorrentes | um gateway lento não consome todas as threads do serviço |

O retry é seguro **porque a cobrança é idempotente** (chave = id do pagamento; ADR-003).

### Quando tudo falha: DLT e reconciliação

- Circuit breaker aberto → a exceção sobe para o listener Kafka → **retry com backoff exponencial + jitter**
  (5 tentativas) → **DLT** (`inventory.events.DLT`). O pagamento fica `PENDING`.
- Um **job de reconciliação** (`PaymentReconciler`, a cada 30s) pega `PENDING` antigos (> 60s) e consulta o gateway:
  cobrou → `AUTHORIZED`; recusou → `DECLINED`; o gateway **nunca viu** a cobrança e passou `give-up-after` (10min)
  → `DECLINED (reconciliation_timeout)`, e a saga **compensa** (estoque liberado, pedido `CANCELLED`).
  Também conclui estornos pendentes de pagamentos cancelados.
- Seguro com várias réplicas: a resolução é "UPDATE somente se ainda `PENDING`" (lock de linha) e o gateway é idempotente.

## Consequências

- (+) Provado: 40% de HTTP 503 absorvidos por retry (25/25 pedidos confirmados, 25 cobranças); gateway 100% fora → breaker abre,
  ao voltar tudo converge sem reserva vazada; PENDING com cobrança já feita no gateway é resolvido pela reconciliação sem cobrar de novo.
- (−) Uma mensagem na DLT **não é reprocessada sozinha**; a reconciliação cobre o caso de pagamento (runbook descreve a repescagem manual).
- (−) O gateway simulado roda dentro do próprio payment-service (`/sim-gateway`, estado no banco para ser consistente entre réplicas):
  representa um terceiro, mas compartilha o ciclo de vida do serviço.

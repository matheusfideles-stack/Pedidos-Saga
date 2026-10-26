# Resultados de caos e resiliência

Todos os cenários são **testes automatizados** (módulo `integration-tests`) e rodam a cada `mvn verify`.

## 1. Queda do payment-service no meio da saga (`T05_ChaosPaymentCrashTest`)

**Hipótese**: se o payment-service morrer (kill -9) com um pagamento `PENDING` **e a cobrança já registrada no gateway**
(a resposta se perdeu), ao voltar o sistema converge sem cobrar nem reservar duas vezes.

**Procedimento** (processo separado, não um mock):

1. payment-service sobe como **processo JVM independente**, com o gateway configurado para *registrar a cobrança e demorar 30s a responder*
   (`GATEWAY_POST_COMMIT_DELAY_MS=30000`) — o resultado ambíguo clássico.
2. Cria-se um pedido (`P-005` × 2). O teste espera: pagamento em `PENDING` **e** linha em `gateway_charges` (cobrança efetivada no gateway).
3. `Process.destroyForcibly()` — **kill -9**. Estado no momento da falha: pedido `PENDING/INVENTORY_RESERVED`, pagamento `PENDING`,
   offset do Kafka **não** confirmado.
4. Sobe um **novo** payment-service saudável. O evento `InventoryReserved` é reentregue; o pagamento `PENDING` é reaproveitado;
   a cobrança é repetida **com a mesma chave de idempotência** e o gateway devolve a cobrança original.

**Resultado** (saída real do teste):

```
[T05] payment-service morto (kill -9) com pagamento afad5732-2bd9-45df-8a39-ba2605a14940 PENDING
[T05] convergiu: 1 pagamento, 1 cobranca no gateway, 1 reserva; pedido CONFIRMED
```

Asserções verificadas: pedido `CONFIRMED`; `payments` = 1 linha `AUTHORIZED`; `gateway_charges` = **1** para o pagamento;
`PaymentAuthorized` no outbox = **1**; reserva de estoque = **1** (`2:COMMITTED`).

## 2. Reentrega de eventos (`T02_DuplicateEventsTest`)

O mesmo envelope (mesmo `eventId`) publicado **2, 10 e 100 vezes** em `order.events`, `inventory.events` e `payment.events`:

| Entregas | Duplicatas descartadas (contador `saga.events.processed{outcome=duplicate}`) | Efeito de negócio |
|---|---|---|
| 2 | exatamente 2 por consumidor/tipo | estoque, reservas, pagamento, cobrança e eventos de saída **inalterados** |
| 10 | exatamente 10 | idem |
| 100 | exatamente 100 | idem |

## 3. Réplicas (`T04_ReplicasTest`)

3 réplicas de cada serviço, 180 pedidos concorrentes (10% recusados no pagamento): **0** processamentos simultâneos do mesmo pedido
(interceptor em todo listener de todas as réplicas), 162 `CONFIRMED` + 18 `CANCELLED`, 180 pagamentos (1 por pedido), 162 cobranças no gateway,
nenhuma reserva órfã. Eventos distribuídos entre as réplicas (ex.: `payment-8=278, payment-9=281, inventory-6=178, inventory-7=183, …`).

## 4. Resiliência do gateway e reconciliação (`T06_ResilienceTest`)

| Cenário | Resultado |
|---|---|
| Gateway instável (40% HTTP 503) | 25/25 pedidos `CONFIRMED`, 25 cobranças (nenhuma duplicada); `resilience4j.retry.calls{kind=successful_with_retry} > 0` |
| Gateway 100% fora do ar | **circuit breaker abre**; ao normalizar, todos os pedidos chegam a estado terminal; `CANCELLED` ⇒ reserva `RELEASED`; `CONFIRMED` ⇒ pagamento `AUTHORIZED` |
| `PENDING` antigo cuja cobrança o gateway efetivou (resposta perdida) | reconciliação resolve para `AUTHORIZED`, emite `PaymentAuthorized`, pedido `CONFIRMED`; ao religar o consumidor **não** há segunda cobrança |
| `PENDING` antigo que o gateway nunca viu (prazo estourado) | `DECLINED (reconciliation_timeout)`, pedido `CANCELLED`, estoque liberado |

## 5. Retry com backoff exponencial + jitter e DLT (`T03`, `T07`)

- Falha 2× e recupera na 3ª: processada uma vez, **não** vai para a DLT.
- Falha sempre: 1 + 4 tentativas com intervalos ≥ 200, 400, 800, 1000 ms (+ jitter ≤ 100 ms) e depois **DLT**.
- JSON inválido / dados inválidos: direto para a DLT, **sem retry**, e a partição segue fluindo (um pedido novo confirma depois).

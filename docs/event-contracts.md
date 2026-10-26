# Contratos de eventos e política de compatibilidade

Todo evento é publicado pelo Debezium a partir da tabela `outbox`. O **valor** da mensagem Kafka é o envelope abaixo,
a **chave** é o id do agregado, e o tópico é `<aggregatetype>.events`.

```json
{
  "eventId": "7c0f2a52-3b0e-4c53-9d5e-0d6e8c9d1a11",
  "type": "OrderCreated",
  "version": 1,
  "aggregateId": "b1f3c3d6-1b8a-4d57-9f5d-51d3bde9bbd0",
  "occurredAt": "2026-10-25T18:04:12.331Z",
  "data": { "...": "campos do tipo" }
}
```

Headers Kafka (colocados pelo `EventRouter`): `id` (= eventId), `eventType`, `traceparent` (W3C Trace Context).

| Tópico | Tipo (`type`) | Produtor | `data` (v1) | Consumidores |
|---|---|---|---|---|
| `order.events` | `OrderCreated` | order | `orderId, customerId, items[{productId, quantity, unitPrice}], total, currency` | inventory, analytics |
| | `OrderConfirmed` | order | `orderId` | inventory (efetiva a reserva), analytics |
| | `OrderCancelled` | order | `orderId, reason` | inventory (libera), payment (estorna), analytics |
| `inventory.events` | `InventoryReserved` | inventory | `orderId, customerId, amount, currency` | payment, order |
| | `InventoryRejected` | inventory | `orderId, reason` | order |
| | `InventoryReleased` | inventory | `orderId` | (auditoria) |
| `payment.events` | `PaymentAuthorized` | payment | `orderId, paymentId, amount` | order |
| | `PaymentDeclined` | payment | `orderId, paymentId, reason` | order |
| | `PaymentCancelled` | payment | `orderId, paymentId` | (auditoria) |
| `catalog.events` | `ProductChanged` | order | `productId, name, price, active, version` | cada instância do order-service (invalidação de cache) |

Tópicos de erro: `<tópico>.DLT` (mesma chave, headers `kafka_dlt-*` com a causa).

## Política de compatibilidade (campo `version`)

O contrato é identificado por **(`type`, `version`)**. Regras:

1. **Mudanças compatíveis (sem mudar `version`)**: adicionar campo opcional; adicionar um novo `type`.
   Consumidores **devem ignorar campos desconhecidos** e **eventos de tipo desconhecido** (o código faz isso:
   `FAIL_ON_UNKNOWN_PROPERTIES=false`, `default ->` ignora; o worker Go também).
2. **Mudanças incompatíveis** (remover/renomear campo, mudar tipo ou semântica) exigem **nova `version`** (`2`).
   Passos: (a) consumidores passam a aceitar v1 **e** v2; (b) só então o produtor passa a emitir v2;
   (c) a v1 é removida depois de drenar os tópicos (retenção) e a DLT.
   Durante a migração o produtor pode emitir **as duas versões** (eventos distintos com o mesmo `aggregateId`).
3. Nunca reutilizar um nome de campo com outro significado.
4. `eventId` é imutável e único: é a chave de idempotência. Reemitir "o mesmo fato" **mantém** o `eventId`.
5. `occurredAt` é o tempo do fato (UTC, ISO-8601) e é o que o analytics usa para janelas — não o tempo de processamento.

Um schema registry (Avro/Protobuf + compatibilidade `BACKWARD`) é a evolução natural; aqui o envelope JSON versionado foi escolhido
para manter a stack enxuta (um componente a menos) mantendo a disciplina de contrato.

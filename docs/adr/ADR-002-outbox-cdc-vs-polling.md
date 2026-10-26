# ADR-002: Transactional Outbox publicado por CDC (Debezium), não por polling

- **Status**: aceita
- **Contexto**: "gravar no banco" e "publicar no Kafka" não são atômicos (dual write). Se o serviço cai entre os dois,
  ou o estado muda sem evento (saga trava) ou o evento sai sem o estado (saga inconsistente).

## Decisão

1. Cada serviço grava o evento na tabela `outbox` **na mesma transação** da mudança de negócio
   (`OutboxWriter` exige transação ativa: `Propagation.MANDATORY`).
2. O **Debezium** (Kafka Connect, conector PostgreSQL, `EventRouter`) lê o WAL (replicação lógica `pgoutput`) e publica
   a linha no tópico `<aggregatetype>.events`, com **chave = `aggregateid`** (orderId) e `payload` como valor.
   Não existe polling nem relay próprio nos serviços.

## Comparação

| | CDC (escolhida) | Polling da tabela |
|---|---|---|
| Latência | ms (stream do WAL) | = intervalo de polling (+ carga constante no banco) |
| Carga no banco | leitura sequencial do WAL | `SELECT ... WHERE published = false` a cada tick |
| Ordem por agregado | a ordem do commit no WAL | exige `ORDER BY` + cuidado com transações concorrentes (ids fora de ordem) |
| Concorrência entre réplicas | 1 slot de replicação por banco | `SKIP LOCKED`/lease para não publicar duas vezes |
| Operação | Connect + conector + slot de replicação | só código da aplicação |
| Falha | o slot guarda o WAL (cuidado com disco se o Connect ficar parado) | retoma de onde parou |

## Consequências

- (+) Atomicidade real entre estado e evento; ordem por pedido preservada (mesma chave → mesma partição).
- (+) Entrega **at-least-once** (o Connect pode republicar após falha) → **todo consumidor é idempotente** (ADR-003).
- (−) Mais uma peça operacional. Runbook: slot parado retém WAL (monitorar `pg_replication_slots`).
- (−) Linhas do outbox acumulam; o `wal_level=logical` é pré-requisito do Postgres. Limpeza por job de retenção é um follow-up
  (as linhas já foram capturadas do WAL, podem ser apagadas; o router ignora `DELETE`).
- O contexto de tracing (`traceparent`) é gravado numa coluna do outbox e o router o copia para um **header Kafka**: é assim
  que a saga inteira vira um único trace.
- O contrato do evento é o envelope `{eventId, type, version, aggregateId, occurredAt, data}` (docs/event-contracts.md).

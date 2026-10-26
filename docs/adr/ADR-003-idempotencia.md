# ADR-003: Idempotência em três níveis

- **Status**: aceita
- **Contexto**: a entrega é *at-least-once* (CDC e consumidores podem reentregar), clientes repetem requisições e a
  chamada ao gateway de pagamento pode dar timeout **depois** de cobrar. Efeito exatamente-uma-vez é obtido compondo
  entrega at-least-once com processamento idempotente.

## Decisão

| Nível | Mecanismo | Garantia |
|---|---|---|
| **HTTP** (`POST /orders`) | header `Idempotency-Key` + tabela `idempotency_keys(key, request_hash, response)`, reservada **na mesma transação** que cria o pedido | mesma chave + mesmo payload → mesma resposta gravada; payload diferente → **422**; requisições concorrentes com a mesma chave se serializam no índice único |
| **Consumidores** | tabela `processed_events(event_id, consumer)` gravada **na mesma transação** do efeito de negócio (`EventProcessor`) | duplicata é descartada; se a transação falha, o registro some junto e o evento é reprocessado |
| **Gateway de pagamento** | a chave de idempotência da cobrança é o **id do pagamento**; `payments.order_id` é `UNIQUE` | repetir a cobrança (retry, reentrega, reconciliação) devolve a cobrança original: nunca cobra duas vezes |

Detalhes que importam:
- O payload do `Idempotency-Key` é **canonizado** (itens ordenados e somados por produto) antes do hash:
  `[A:1,B:2]` e `[B:2,A:1]` são o mesmo pedido.
- A chamada de rede ao gateway **não acontece dentro de transação de banco** (segurar conexão durante I/O externo
  esgota o pool). Fluxo: tx1 grava `PENDING` → chamada idempotente → tx2 `PENDING → AUTHORIZED/DECLINED` + outbox + `processed_events`.
  Se o processo morre entre 1 e 3, o offset não foi confirmado e o evento volta.
- Defesas redundantes de domínio: estoque não reserva duas vezes o mesmo pedido (`reservations` com PK `(order_id, product_id)`).

## Consequências

- (+) Provado por testes: o mesmo evento entregue **2, 10 e 100 vezes** produz um único efeito (T02), 8 workers Go processando o mesmo
  lote ao mesmo tempo contam uma vez, e o teste de caos (kill -9 com cobrança em voo) termina com **1 pagamento e 1 cobrança**.
- (−) `processed_events` cresce sem limite → retenção por tempo (maior que a retenção do Kafka) é um follow-up.
- (−) A janela de dedup é "para sempre" enquanto a linha existir; aceitável para um fluxo financeiro.

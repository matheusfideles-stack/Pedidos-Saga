# ADR-001: Saga coreografada (e não orquestrada)

- **Status**: aceita
- **Contexto**: criar um pedido atravessa três serviços com bancos próprios. Sem transação distribuída (2PC proibido),
  a consistência é obtida por uma saga: sequência de transações locais com compensações.

## Opções

| | Coreografada (escolhida) | Orquestrada |
|---|---|---|
| Quem conhece o fluxo | ninguém sozinho: cada serviço reage a eventos e publica o próximo | um orquestrador central com o fluxo inteiro |
| Acoplamento | só ao **contrato dos eventos** | todos os serviços ao orquestrador (comandos) |
| Ponto único de falha/gargalo | não há | o orquestrador (precisa de HA e estado durável) |
| Visibilidade do fluxo | espalhada: exige tracing/auditoria | centralizada, fácil de ler e depurar |
| Complexidade a medida que cresce | cresce rápido (ciclos de eventos, dependências ocultas) | cresce no orquestrador |
| Compensações | cada serviço reage a `OrderCancelled` | o orquestrador manda desfazer cada passo |

## Decisão

Coreografia, com **3 participantes e um fluxo linear curto** (estoque → pagamento → confirmação). O enunciado também pede a
máquina de estados "à mão", sem Axon/Temporal: o objetivo é demonstrar o padrão.

- O **order-service é dono do estado do pedido** (`OrderStateMachine`, função pura): ele consome `InventoryReserved/Rejected` e
  `PaymentAuthorized/Declined` e é o único que decide `CONFIRMED`/`CANCELLED`.
- **Compensações são disparadas por um único evento, `OrderCancelled`**: inventário libera a reserva, pagamento estorna.
  Quem cancela (cliente, falta de estoque, pagamento recusado) não precisa conhecer as compensações.
- Eventos de topics diferentes podem chegar fora de ordem; a máquina de estados trata isso explicitamente
  (ex.: `PaymentAuthorized` com o pedido ainda `PENDING` é aceito; eventos tardios são ignorados) e há testes unitários para cada caso.

## Consequências

- (+) Sem componente central; cada serviço escala e falha de forma independente.
- (+) Adicionar um consumidor (como o `analytics-worker`) não altera ninguém.
- (−) O fluxo não está escrito em um lugar só. Mitigação: este ADR, o diagrama do README, `order_status_history`
  (auditoria de cada transição) e **um trace único por saga** (ver README, Observabilidade).
- (−) Risco de **ciclos de eventos** se o número de participantes crescer. Gatilho para revisitar: > 5 passos, ramificações
  condicionais ou necessidade de timeouts de negócio por etapa → migrar para orquestração.
- Corrida cancelamento × cobrança em voo: tratada com *tombstone* no pagamento (cancelamento antes da cobrança registra um
  `CANCELLED` que impede cobrar depois) e estorno automático se a autorização chegar após o cancelamento.

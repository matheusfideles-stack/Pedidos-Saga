# ADR-005: Chave de partição = `orderId` e número de partições

- **Status**: aceita

## Decisão

1. **Chave da mensagem = id do agregado** (`orderId`; `productId` no catálogo). O Debezium `EventRouter` usa `aggregateid` como chave.
2. **6 partições** por tópico (`order.events`, `inventory.events`, `payment.events`, `catalog.events` e as `*.DLT`).

## Por quê a chave

Kafka só garante ordem **dentro de uma partição**; a mesma chave vai sempre para a mesma partição. Com `orderId` como chave:

- todos os eventos de **um pedido** são consumidos em ordem (`OrderCreated` antes de `OrderCancelled`) — a compensação nunca
  chega antes da reserva no inventário;
- **nenhum evento do mesmo pedido é processado em paralelo** em um mesmo consumer group, mesmo com N réplicas
  (cada partição pertence a um único consumidor do grupo). Provado em T04: 3 réplicas de cada serviço, 180 pedidos
  concorrentes, **0** processamentos simultâneos do mesmo pedido, medido por um interceptor em todo listener;
- pedidos diferentes se espalham pelas partições (paralelismo).

Ordem **entre tópicos** não é garantida (ex.: `PaymentAuthorized` pode ser lido antes de `InventoryReserved` pelo order-service);
por isso a máquina de estados é tolerante a reordenação (ADR-001).

## Por quê 6 partições (relação com réplicas)

O paralelismo máximo de um consumer group é **partições**: threads consumidoras ativas = min(partições, réplicas × concorrência).

- `KAFKA_CONCURRENCY=2` por réplica no Kubernetes → **3 réplicas × 2 threads = 6** consumidores ativos = 6 partições.
  Réplicas além de 3 não consomem mais rápido (ficam ociosas para Kafka; só ajudam a parte HTTP do order-service).
  Por isso o HPA dos consumidores tem `maxReplicas: 3` (overlay KEDA por lag usa o mesmo teto).
- Aumentar partições permite escalar mais, mas: **não pode ser reduzida**, e **mudar o número de partições muda o
  mapeamento chave→partição** (quebra a ordem para pedidos em voo). Faça fora de pico, com a saga drenada.
- 6 é um meio-termo para o escopo: cabe em 1 broker de dev, deixa folga para 2× o número de réplicas testado.

## Consequências

- (+) Ordem por pedido, paralelismo entre pedidos, escala previsível.
- (−) **Chave quente**: um agregado muito ativo satura uma partição (não é o caso: ~5 eventos por pedido).
- (−) Escalar além de 6 consumidores exige aumentar partições (operação delicada, acima).
- DLT usa partição `-1` (o produtor escolhe pela chave) para manter a ordem por pedido também na DLT.

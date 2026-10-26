# ADR-004: Cache do catálogo — cache-aside em duas camadas (Caffeine L1 + Redis L2)

- **Status**: aceita
- **Contexto**: o order-service lê o produto (preço/ativo) a cada pedido. O catálogo muda raramente, é lido muito e
  um pico de leituras na mesma chave fria não pode virar um pico no banco.

## Decisão

```
leitura → L1 Caffeine (por instância, TTL ~30s) → L2 Redis (compartilhado, TTL ~5min) → PostgreSQL
```

| Problema | Solução |
|---|---|
| Expiração simultânea (avalanche) | **TTL com jitter** ±20% em L1, L2 e cache negativo |
| Cache stampede | **single-flight** por chave no L1 (Caffeine computa 1×) + **lock distribuído** no Redis (`SET NX PX`) na recarga entre instâncias; quem perde o lock espera o Redis ser populado (até 1s) e só então cai no banco (*fail-open*) |
| Penetração (produto inexistente) | **cache negativo** com TTL curto (15s) |
| Dado velho após alteração | **invalidação orientada a eventos**: `PUT /catalog` grava `ProductChanged` no outbox → Debezium → Kafka → **toda instância** remove a chave (L1 local + L2). Cada instância tem um *consumer group próprio* (L1 é local) lendo do fim do tópico |
| Leitura antiga recacheada após invalidação | cada entrada carrega a `version` do produto; a instância guarda a maior versão invalidada e não recacheia leituras mais velhas |
| Redis fora do ar | timeouts curtos (500ms); leitura **degrada para o banco** (não derruba criação de pedidos); métrica de erros |
| Observabilidade | contadores e **hit ratio por camada** (`catalog.cache.hit.ratio{layer=l1|l2}`), `GET /catalog/cache/stats` |

## Por que invalidar depois do commit (e não antes)

Invalidar no serviço antes de commitar permitiria que um leitor concorrente recarregasse o valor **antigo** e o mantivesse até o TTL.
Com o evento (pós-commit) o banco já tem o valor novo quando a chave é removida.

## Consequências

- (+) Provado por testes: 300 threads na mesma chave fria → **1** leitura no banco; 200 leituras simultâneas em **2 instâncias** → **1** leitura;
  alteração de preço visível nas duas instâncias via CDC mesmo com L1 de 10 minutos (prova que foi o evento, não o TTL).
- (−) Consistência **eventual**: entre o commit e a chegada do evento (ms a ~1s) um pedido pode usar o preço antigo.
  Aceitável aqui (o preço usado é registrado no pedido). Se não fosse, validar o preço no banco dentro da transação do pedido.
- (−) O L1 por instância multiplica a memória por réplica (limitado por `l1-max-size`).
- (−) Grupos efêmeros `order-service-cache-<uuid>` se acumulam no Kafka; expiram quando vazios (retenção de offsets).

# ADR-007: Infraestrutura de testes — Testcontainers com *fallback* embarcado

- **Status**: aceita (os dois modos foram executados e passam: 62/62)
- **Contexto**: o enunciado pede testes de integração com Testcontainers. A máquina onde o projeto foi desenvolvido
  inicialmente **não conseguia rodar Docker** (Docker Desktop sem virtualização) e os testes precisavam rodar e provar comportamento real
  mesmo assim; depois o Docker passou a funcionar e os dois modos foram executados (o que revelou o problema do Docker Engine 29 descrito no README).

## Decisão

`TestInfra` (módulo `test-support`) escolhe o modo automaticamente (ou `-Dsaga.test.infra=docker|embedded`):

| | Modo DOCKER (padrão se houver Docker) | Modo EMBEDDED (fallback) |
|---|---|---|
| PostgreSQL | Testcontainers `postgres:16-alpine` (`wal_level=logical`) | PostgreSQL 16 nativo (Zonky), `wal_level=logical` |
| Kafka | Testcontainers `apache/kafka` (KRaft) | broker KRaft embarcado (`spring-kafka-test`) |
| Redis | Testcontainers `redis:7-alpine` | `redis-server` embarcado |
| CDC | **Debezium Embedded Engine** (mesmo conector PostgreSQL e **mesmo `EventRouter`**) | idem |

Os **mesmos testes** rodam nos dois modos; nada é mockado: banco, broker, CDC e cache são reais.

## O que NÃO é idêntico (honestidade)

- No modo Docker, o CDC dos testes usa o **Embedded Engine** do Debezium dentro do JVM de teste, não o container do Kafka Connect.
  A configuração do conector e do router é a mesma (`infra/debezium/*.json`), mas o processo Connect em si só é exercitado pelo
  `docker-compose`/Kubernetes (`scripts/smoke-test.sh`). O relay de teste publica em lote e só avança o offset do WAL após o `flush()`
  no Kafka, imitando o Connect.
- O modo embarcado roda tudo no mesmo SO/JVM: bom para lógica e consistência, **não** para medir desempenho absoluto
  (os números de carga do README são relativos: 1 réplica × 3 réplicas na mesma máquina).
- O teste de caos usa **processo separado** (`kill -9` real via `Process.destroyForcibly`), não só fechar o contexto Spring.

## Consequências

- (+) Testes rodam em qualquer máquina (CI sem Docker incluso) e em Docker quando disponível.
- (−) Dois caminhos de infra para manter. Mitigação: a abstração é pequena (~200 linhas) e o CI deve rodar os dois modos.
- (−) Os manifests Kubernetes **não foram executados** em cluster (o compose e o Kafka Connect sim; ver README, seção *Verificado*).

# Resultados do teste de carga (k6) — antes e depois de escalar

Gerado por `scripts/load-test.sh` (k6 v2.3.0, `load-test/orders.js`). JSON brutos nesta pasta
(`1-replica.json`, `3-replicas.json` e `*-drain.json`).

## Cenário

- **Carga**: modelo aberto (`constant-arrival-rate`) de **60 pedidos/s por 60s** (3.600 pedidos), `POST /orders` com `Idempotency-Key`,
  1–2 itens, 5% de clientes recusados no pagamento (exercita a compensação sob carga). ~5% dos pedidos são acompanhados por
  `GET /orders/{id}` até o estado final para medir a saga ponta a ponta.
- **O que muda entre as execuções**: **1 réplica** × **3 réplicas** de *cada* serviço (order, inventory, payment), stack limpa em cada execução.
- **Ambiente**: `local-stack` (PostgreSQL, Kafka KRaft, Redis e Debezium **embarcados**, serviços em JDK 21), **tudo na mesma máquina**
  (Windows 10, 12 CPUs) — incluindo o k6. Gateway de pagamento simulado com latência de 30ms (+ jitter até 20ms).
  `KAFKA_CONCURRENCY=2` por réplica, 6 partições por tópico.

## Resultados

| | 1 réplica | 3 réplicas |
|---|---|---|
| **Criação de pedido** (`POST /orders`, síncrona) — throughput | 60,0 /s | 60,0 /s |
| criação — p95 | 12,6 ms | 13,0 ms |
| criação — p99 | 40,1 ms | 26,0 ms |
| criação — erros | 0% | 0% |
| **Saga ponta a ponta** (criação → CONFIRMED/CANCELLED) — p50 | **17,6 s** | **0,76 s** |
| saga — p95 | **33,2 s** | **1,37 s** |
| saga — p99 | **35,6 s** | **2,01 s** |
| Tempo até **todos** os 3.600 pedidos terminarem a saga | **198 s** | **68 s** |
| Vazão média da saga (pedidos concluídos/s, do início ao fim) | 18,2 | 53,0 |

## Leitura

- A parte **síncrona** (criar o pedido: 1 transação + outbox) é barata e **não é o gargalo**: p95 ≈ 13 ms nas duas configurações.
  O trabalho caro é **assíncrono** e está nos consumidores.
- Com 1 réplica há 2 threads consumindo por serviço; a cobrança bloqueia uma thread durante a chamada ao gateway (≈ 30–50 ms), o que limita o
  *throughput* do `payment-service` a ~30–40 cobranças/s. A 60 pedidos/s a fila cresce (lag) e a latência da saga explode
  (p50 17 s) — mas **nada se perde**: o sistema drena e todos os pedidos convergem.
- Com 3 réplicas (6 threads = 6 partições) o consumo acompanha a chegada: latência da saga ~**23× menor** no p50 e a fila drena em
  segundos. **O throughput de criação não muda porque não era o limite**; o ganho de escalar aparece na convergência.
- **Limitações desta medição** (ver ADR-007): tudo compartilha CPU/disco de uma máquina e o PostgreSQL é único e embarcado, então os valores
  absolutos não valem para produção; o que vale é a **relação** entre as execuções e o comportamento (fila cresce → escala → fila some).
  Executar `make up3` + `scripts/load-test.sh` contra o `docker compose`/Kubernetes é o próximo passo para números de ambiente real.

## Como reproduzir

```bash
export K6=/caminho/para/k6        # ou k6 no PATH
./scripts/load-test.sh 1-replica  1 60 60s
./scripts/load-test.sh 3-replicas 3 60 60s
```

## Execução adicional: docker compose real (3 réplicas de cada serviço)

Mesma carga (60 pedidos/s por 60s, k6 → nginx `:8080`), agora contra `docker compose` com **Kafka Connect + Debezium reais**,
containers de PostgreSQL/Kafka/Redis e 3 réplicas de cada serviço (`REPLICAS=3 ./scripts/up.sh`), Docker Desktop na mesma máquina do k6
(`load-test/results/compose-3-replicas.json`):

| | compose, 3 réplicas |
|---|---|
| criação (`POST /orders`): throughput · p95 · p99 · erros | 60/s · 45,5 ms · 77,6 ms · 0% |
| saga ponta a ponta p50 · p95 · p99 | 2,8 s · 4,3 s · 4,6 s |
| pedidos criados / amostrados até o estado final | 3.600 / 190 (185 confirmados + 5 cancelados, 0 sem estado final) |

Os valores são maiores que os do `local-stack` porque aqui há containers, rede virtual do Docker, o nginx e o Connect real no caminho;
ainda assim a saga acompanhou a chegada (latência estável, sem fila crescente) e nenhuma requisição falhou; a conclusão é medida por amostragem (1 em 20), não por contagem total. Não há execução "1 réplica" no compose para comparar.

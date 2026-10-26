# Throughput: consumidor Go x Java (analytics)

Gerado por `AnalyticsBenchmark` em 2026-10-25T18:21:19.293438400Z.

- **Carga**: 169.938 eventos de pedido pre-carregados em `analytics.bench` (6 particoes), 120 janelas de 1 minuto.
- **Mesmo algoritmo nos dois**: lote de ate 500 mensagens -> 1 transacao (registra `eventId` + upsert das janelas) -> commit do offset **depois** do commit no banco.
- **Ambiente**: tudo na mesma maquina (PostgreSQL e Kafka embarcados, Windows 10, 12 CPUs), JDK 21.0.12.1. Numeros sao RELATIVOS: o gargalo e o PostgreSQL local compartilhado, nao a linguagem.

| Instancias do consumer group | Java (eventos/s) | Go (eventos/s) | Go / Java |
|---|---|---|---|
| 1 | 44.494 | 51.250 | 1,15x |
| 3 | 91.451 | 76.979 | 0,84x |

Medicao: tempo entre o primeiro evento gravado e o ultimo (exclui o join no grupo).

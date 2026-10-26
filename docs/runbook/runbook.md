# Runbook — o que fazer quando…

Comandos para `docker compose`; no Kubernetes troque `docker compose exec kafka` por `kubectl -n pedidos-saga exec -it kafka-0 --`
e os logs por `kubectl -n pedidos-saga logs deploy/<servico>`. Dashboard: `infra/datadog/dashboard.json`.
Nomes de grupo: `order-service`, `inventory-service`, `payment-service`, `analytics-worker`.

Regra de ouro: **todo consumidor é idempotente** (`processed_events`) e a cobrança é idempotente pelo id do pagamento →
**reprocessar/republicar mensagens é seguro**. Em dúvida, republique.

---

## A DLT cresce

**Sintoma**: monitor *"[Saga] DLT crescendo"*; métrica `saga.dlt.published{topic}`; pedidos parados em estado intermediário.

1. **Qual DLT e por quê.** Cada mensagem na DLT carrega a causa nos headers:
   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
     --topic inventory.events.DLT --from-beginning --max-messages 5 \
     --property print.key=true --property print.headers=true
   ```
   Veja `kafka_dlt-original-topic`, `kafka_dlt-exception-message`, `kafka_dlt-exception-stacktrace`.
2. **Classifique a causa**
   | Causa nos headers | Natureza | Ação |
   |---|---|---|
   | `PoisonMessageException` (JSON inválido, dados inválidos) | permanente — **bug do produtor ou contrato quebrado** | corrigir o produtor/versão do contrato (`docs/event-contracts.md`); a mensagem **não** deve ser republicada como está |
   | `CallNotPermittedException` / `TransientGatewayException` | transitória (gateway/breaker) | seguir **"O circuit breaker abre"**; depois republicar |
   | exceção de banco (timeout, deadlock, conexão) | transitória | verificar saúde do PostgreSQL/pool; depois republicar |
3. **Cheque o efeito no negócio.** Pagamentos `PENDING` são resolvidos sozinhos pela reconciliação (até `give-up-after`, 10min, depois `DECLINED` e a saga compensa).
   Pedidos presos:
   ```sql
   -- order-db
   SELECT status, count(*), min(created_at) FROM orders WHERE status NOT IN ('CONFIRMED','CANCELLED') GROUP BY 1;
   ```
4. **Republicar** (após corrigir a causa) as mensagens da DLT para o tópico original, **mantendo a chave**:
   ```bash
   docker compose exec kafka sh -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
     --topic inventory.events.DLT --from-beginning --timeout-ms 5000 \
     --property print.key=true --property key.separator="|" \
   | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic inventory.events \
     --property parse.key=true --property key.separator="|"'
   ```
   Duplicatas são descartadas pelos consumidores; o `eventId` original é preservado.
5. **Depois**: confirme que `saga.dlt.published` parou de crescer e o lag voltou a 0. Registre a causa raiz; DLT nunca deve ser "limpa" sem análise.

---

## Lag do consumer sobe

**Sintoma**: monitor *"[Saga] Lag alto"* (`kafka.consumer_lag > 500`); `saga.event.latency` (p95) sobe; pedidos demoram a confirmar.

1. **Qual grupo/tópico**:
   ```bash
   docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group payment-service
   ```
   Olhe `LAG` por partição e se há `CONSUMER-ID` em todas (partição sem dono = rebalance ou réplicas insuficientes).
2. **Lag em todas as partições** → o consumidor é o gargalo:
   - `payment-service`: latência do gateway (`payment.gateway.charge` p95). Cada cobrança bloqueia uma thread pela latência do gateway;
     throughput por thread ≈ 1/latência. Se o gateway está lento, o lag é consequência (ver *circuit breaker*).
   - qualquer serviço: pool de conexões esgotado / banco lento (`hikaricp_connections_pending`, CPU do PostgreSQL).
   - **Escalar**: aumente réplicas (HPA por CPU ou KEDA por lag). **Teto = partições / concorrência** (6 / 2 = 3 réplicas por serviço);
     além disso não adianta (ADR-005). Para ir além, aumente partições (planejado, com a saga drenada).
3. **Lag em uma só partição** → mensagem lenta/travada ou chave quente. Veja os logs do consumidor dono dessa partição
   (`orderId`/`eventId` no JSON do log) e se há retry em andamento (backoff exponencial até ~16s por mensagem antes da DLT).
4. **Lag que cresce logo depois de deploy/queda** → rebalance. Normal por alguns segundos (`session.timeout`); se persistir, cheque se
   pods estão reiniciando (`kubectl get pods`, probe de liveness falhando por GC/CPU).
5. **Efeito colateral aceitável**: lag só atrasa a convergência (consistência eventual); estados intermediários explícitos
   (`INVENTORY_RESERVED`, `PAYMENT_AUTHORIZED`) mostram onde cada pedido está. Nada se perde: o offset só avança após commit no banco.

Se o **Debezium** estiver atrás (eventos demoram a aparecer nos tópicos): `curl localhost:8084/connectors/<nome>/status`;
conector `FAILED` → logs do `kafka-connect`, depois `curl -X POST localhost:8084/connectors/<nome>/restart?includeTasks=true`.
Atenção: **slot de replicação parado retém WAL** no PostgreSQL (`SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) FROM pg_replication_slots;`).

---

## O circuit breaker abre

**Sintoma**: monitor *"[Saga] Circuit breaker do gateway aberto"* (`resilience4j.circuitbreaker.state{name:gateway,state:open}`);
cobranças falham rápido (`CallNotPermittedException`); pagamentos ficam `PENDING`; DLT pode começar a receber `InventoryReserved`.

O breaker abre quando ≥ 50% das últimas 20 chamadas falham (mínimo 10). **Ele não é o problema: é o sintoma de que o gateway está ruim.**

1. **Confirme o gateway**: latência/erro do gateway (`payment.gateway.charge`, taxa de `kind:failed` em `resilience4j.circuitbreaker.calls`),
   status page do provedor. No ambiente local, `GATEWAY_FAILURE_RATE`/`GATEWAY_LATENCY_MS` do payment-service controlam o simulador.
2. **Não reinicie os pods** nem aumente retries: isso só aumenta a carga sobre um gateway doente. Após 10s o breaker vai a *half-open*
   e testa 3 chamadas; se passarem, fecha sozinho.
3. **Estado dos pagamentos**:
   ```sql
   -- payment-db
   SELECT status, count(*), min(created_at) FROM payments GROUP BY 1;
   ```
   - `PENDING`: a **reconciliação** (a cada 30s) consulta o gateway e resolve os que foram cobrados; os que o gateway nunca viu
     são recusados após `give-up-after` (10min) e a saga cancela o pedido e libera o estoque. Para encurtar durante uma indisponibilidade
     longa: reduza `RECONCILIATION_GIVE_UP_AFTER` e reinicie com rolling update.
   - mensagens que foram para a DLT: republique (seção *A DLT cresce*, passo 4) quando o breaker fechar. É seguro.
4. **Se o gateway ficará fora por muito tempo**: decisão de negócio — manter pedidos em `PENDING` (cliente aguarda) ou aceitar o
   cancelamento automático por `reconciliation_timeout`. O inventário nunca fica reservado indefinidamente: toda compensação parte de `OrderCancelled`.
5. **Pós-incidente**: confirme `breaker CLOSED`, DLT parada, `PENDING` antigos zerados (query acima) e que não há reservas órfãs:
   ```sql
   -- inventory-db: reservas ativas de pedidos que o order-service já cancelou (deve ser vazio após convergir)
   SELECT r.order_id FROM reservations r WHERE r.status = 'RESERVED' AND r.created_at < now() - interval '15 minutes';
   ```

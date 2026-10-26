package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.it.SagaEnvironment.RunningApp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Escala horizontal: 3 replicas de CADA servico consumindo os mesmos topicos (mesmo consumer group).
 * Prova: nenhum evento e processado em paralelo para o mesmo pedido, nada e perdido/duplicado e a carga
 * se distribui entre as replicas.
 */
class T04_ReplicasTest {

    private static SagaEnvironment env;
    private static final int ORDERS = 180;

    @BeforeAll
    static void scaleOut() throws Exception {
        env = SagaEnvironment.get();
        for (String service : List.of("order", "inventory", "payment")) {
            while (env.running(service).size() < 3) {
                env.start(service);
            }
        }
        // aquecimento: espera o rebalanceamento dos grupos terminar com um pedido completo
        var warm = env.createOrder("scale-warmup", "P-008", 1);
        env.awaitStatus(warm.id(), "CONFIRMED", Duration.ofSeconds(120));
        env.probe.reset();
    }

    @AfterAll
    static void scaleIn() {
        for (String service : List.of("order", "inventory", "payment")) {
            List<RunningApp> list = env.running(service);
            list.stream().skip(1).forEach(env::stop);
        }
    }

    @Test
    @DisplayName("3 replicas de cada servico: zero processamento paralelo por pedido, nada perdido, carga distribuida")
    void noParallelProcessingPerOrderAcrossReplicas() throws Exception {
        assertThat(env.running("order")).hasSize(3);
        assertThat(env.running("inventory")).hasSize(3);
        assertThat(env.running("payment")).hasSize(3);

        String[] products = {"P-001", "P-002", "P-004", "P-005", "P-006", "P-007", "P-008"};
        ExecutorService pool = Executors.newFixedThreadPool(24);
        long start = System.nanoTime();
        List<Future<SagaEnvironment.Created>> created = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            int n = i;
            String customer = (n % 10 == 0 ? "decline-" : "scale-") + n; // 10% serao recusados no pagamento
            created.add(pool.submit(() -> env.createOrder(customer, products[n % products.length], 1 + n % 3,
                    products[(n + 3) % products.length], 1)));
        }
        List<java.util.UUID> ids = new ArrayList<>();
        for (Future<SagaEnvironment.Created> f : created) {
            assertThat(f.get().status()).isEqualTo(201);
            ids.add(f.get().id());
        }
        pool.shutdown();

        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            String in = ids.stream().map(u -> "'" + u + "'").collect(Collectors.joining(","));
            Long open = env.orderDb().queryForObject(
                    "SELECT count(*) FROM orders WHERE id IN (" + in + ") AND status NOT IN ('CONFIRMED','CANCELLED')",
                    Long.class);
            return open == 0;
        });
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("[T04] %d pedidos ate o estado terminal em %.1fs (%.1f pedidos/s) com 3 replicas/servico%n",
                ORDERS, seconds, ORDERS / seconds);

        String in = ids.stream().map(u -> "'" + u + "'").collect(Collectors.joining(","));
        long confirmed = env.orderDb().queryForObject(
                "SELECT count(*) FROM orders WHERE id IN (" + in + ") AND status = 'CONFIRMED'", Long.class);
        long cancelled = env.orderDb().queryForObject(
                "SELECT count(*) FROM orders WHERE id IN (" + in + ") AND status = 'CANCELLED'", Long.class);
        assertThat(confirmed).isEqualTo(ORDERS - ORDERS / 10);
        assertThat(cancelled).isEqualTo(ORDERS / 10);

        // === a prova central: nenhum processamento concorrente do mesmo pedido, em nenhuma replica ===
        assertThat(env.probe.violations()).as("processamentos concorrentes do mesmo pedido").isZero();

        // sem perda nem duplicidade de efeitos
        assertThat(env.paymentDb().queryForObject("SELECT count(*) - count(DISTINCT order_id) FROM payments",
                Long.class)).isZero();
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM payments WHERE order_id IN (" + in + ")",
                Long.class)).isEqualTo((long) ORDERS);
        assertThat(env.paymentDb().queryForObject(
                "SELECT count(*) FROM gateway_charges g JOIN payments p ON p.id::text = g.idempotency_key "
                        + "WHERE g.status = 'AUTHORIZED' AND p.order_id IN (" + in + ")", Long.class))
                .isEqualTo(confirmed);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(env.inventoryDb().queryForObject("SELECT count(*) FROM reservations WHERE order_id IN (" + in
                    + ") AND status = 'COMMITTED'", Long.class)).isGreaterThanOrEqualTo(confirmed);
            assertThat(env.inventoryDb().queryForObject("SELECT count(*) FROM reservations WHERE order_id IN (" + in
                    + ") AND status = 'RESERVED'", Long.class)).isZero();
        });

        // a carga foi distribuida: mais de uma replica de cada servico processou eventos
        Map<String, Long> perInstance = env.probe.perInstance().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum()));
        System.out.println("[T04] eventos por replica: " + perInstance);
        for (String service : List.of("order", "inventory", "payment")) {
            long active = perInstance.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(service + "-") && e.getValue() > 0).count();
            assertThat(active).as("replicas ativas de " + service).isGreaterThanOrEqualTo(2);
        }
    }
}

package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.it.SagaEnvironment.Created;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Entrega at-least-once: o MESMO evento (mesmo eventId) e reentregue 2, 10 e 100 vezes a cada consumidor.
 * Prova: o efeito de negocio acontece uma unica vez e as demais entregas sao descartadas (processed_events).
 */
class T02_DuplicateEventsTest {

    private static SagaEnvironment env;
    private static KafkaProbe kafka;

    @BeforeAll
    static void start() {
        env = SagaEnvironment.get();
        kafka = new KafkaProbe(env.infra.kafkaBootstrap());
    }

    private static double duplicatesDiscarded(String consumer, String type) {
        double sum = 0;
        for (var app : env.running(consumer.replace("-service", ""))) {
            MeterRegistry registry = app.ctx().getBean(MeterRegistry.class);
            Counter c = registry.find("saga.events.processed").tag("consumer", consumer).tag("type", type)
                    .tag("outcome", "duplicate").counter();
            if (c != null) {
                sum += c.count();
            }
        }
        return sum;
    }

    private static String payload(org.springframework.jdbc.core.JdbcTemplate db, UUID orderId, String type) {
        return db.queryForObject("SELECT payload FROM outbox WHERE aggregateid = ? AND type = ?", String.class,
                orderId.toString(), type);
    }

    @ParameterizedTest(name = "mesmo evento entregue {0} vezes a cada consumidor => efeito unico")
    @ValueSource(ints = {2, 10, 100})
    @DisplayName("idempotencia dos consumidores")
    void duplicatesHaveSingleEffect(int times) throws Exception {
        Created order = env.createOrder("dup-" + times, "P-006", 3);
        env.awaitStatus(order.id(), "CONFIRMED", Duration.ofSeconds(90));
        String id = order.id().toString();
        // o estoque efetiva a reserva (COMMITTED) ao receber OrderConfirmed: espere o sistema assentar
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> "COMMITTED".equals(
                env.inventoryDb().queryForObject("SELECT status FROM reservations WHERE order_id = ?", String.class,
                        order.id())));

        // estado de referencia ANTES das duplicatas
        int available = env.stock("P-006", "available");
        int reserved = env.stock("P-006", "reserved");
        long historyBefore = env.statusHistory(order.id()).size();
        double dupInventory = duplicatesDiscarded("inventory-service", "OrderCreated");
        double dupPaymentReserved = duplicatesDiscarded("payment-service", "InventoryReserved");
        double dupOrderReserved = duplicatesDiscarded("order-service", "InventoryReserved");
        double dupOrderAuthorized = duplicatesDiscarded("order-service", "PaymentAuthorized");

        // reentrega do mesmo envelope (mesmo eventId) varias vezes
        kafka.publish("order.events", id, payload(env.orderDb(), order.id(), "OrderCreated"), times);
        kafka.publish("inventory.events", id, payload(env.inventoryDb(), order.id(), "InventoryReserved"), times);
        kafka.publish("payment.events", id, payload(env.paymentDb(), order.id(), "PaymentAuthorized"), times);
        kafka.awaitLagZero(Duration.ofSeconds(120), "order-service", "inventory-service", "payment-service");

        // os consumidores descartaram TODAS as duplicatas
        assertThat(duplicatesDiscarded("inventory-service", "OrderCreated") - dupInventory).isEqualTo(times);
        assertThat(duplicatesDiscarded("payment-service", "InventoryReserved") - dupPaymentReserved).isEqualTo(times);
        assertThat(duplicatesDiscarded("order-service", "InventoryReserved") - dupOrderReserved).isEqualTo(times);
        assertThat(duplicatesDiscarded("order-service", "PaymentAuthorized") - dupOrderAuthorized).isEqualTo(times);

        // ... e o efeito de negocio nao se repetiu
        assertThat(env.stock("P-006", "available")).isEqualTo(available);
        assertThat(env.stock("P-006", "reserved")).isEqualTo(reserved);
        assertThat(env.inventoryDb().queryForObject("SELECT count(*) FROM reservations WHERE order_id = ?", Long.class,
                order.id())).isEqualTo(1L);
        assertThat(env.inventoryDb().queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregateid = ? AND type = 'InventoryReserved'", Long.class, id))
                .isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class,
                order.id())).isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM gateway_charges WHERE status = 'AUTHORIZED' "
                + "AND idempotency_key IN (SELECT id::text FROM payments WHERE order_id = ?)", Long.class,
                order.id())).isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregateid = ? AND type = 'PaymentAuthorized'", Long.class, id))
                .isEqualTo(1L);
        assertThat(env.statusHistory(order.id())).hasSize((int) historyBefore);
        assertThat(env.orderStatus(order.id())).isEqualTo("CONFIRMED");
        assertThat(env.orderDb().queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregateid = ? AND type = 'OrderConfirmed'", Long.class, id))
                .isEqualTo(1L);
    }
}

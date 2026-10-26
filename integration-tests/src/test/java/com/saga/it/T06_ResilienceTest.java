package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.it.SagaEnvironment.Created;
import com.saga.it.SagaEnvironment.RunningApp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Etapa 6: Resilience4j no gateway, reconciliacao de pagamentos PENDING e convergencia apos indisponibilidade. */
class T06_ResilienceTest {

    private static SagaEnvironment env;
    private static KafkaProbe kafka;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @BeforeAll
    static void start() {
        env = SagaEnvironment.get();
        kafka = new KafkaProbe(env.infra.kafkaBootstrap());
    }

    @AfterEach
    void restoreHealthyPayment() {
        env.stopAll("payment");
        env.start("payment");
    }

    private static RunningApp restartPayment(String... args) {
        env.stopAll("payment");
        return env.start("payment", args);
    }

    private static void pausePaymentListener(RunningApp app) {
        app.ctx().getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-saga").stop();
    }

    private static void resumePaymentListener(RunningApp app) {
        app.ctx().getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("payment-saga").start();
    }

    /** Pedido que ja teve o estoque reservado, com o consumidor de pagamento pausado (nenhuma cobranca feita). */
    private static Created orderWaitingForPayment(String customer) {
        Created order = env.createOrder(customer, "P-008", 1);
        await().atMost(TIMEOUT).until(() -> "INVENTORY_RESERVED".equals(env.orderStatus(order.id())));
        return order;
    }

    private static UUID craftStalePending(Created order, boolean gatewayKnowsCharge) {
        UUID paymentId = UUID.randomUUID();
        env.paymentDb().update("INSERT INTO payments (id, order_id, customer_id, amount, currency, status, created_at, "
                + "updated_at) VALUES (?, ?, 'crafted', 60.00, 'BRL', 'PENDING', now() - interval '1 minute', "
                + "now() - interval '1 minute')", paymentId, order.id());
        if (gatewayKnowsCharge) {
            env.paymentDb().update("INSERT INTO gateway_charges (idempotency_key, customer_id, amount, status, ref) "
                    + "VALUES (?, 'crafted', 60.00, 'AUTHORIZED', 'gw_crafted')", paymentId.toString());
        }
        return paymentId;
    }

    @Test
    @DisplayName("reconciliacao: PENDING antigo e resolvido com o estado REAL do gateway (cobrou, mas resposta se perdeu)")
    void reconcilerResolvesPendingFromGateway() throws Exception {
        RunningApp payment = restartPayment();
        pausePaymentListener(payment);
        Created order = orderWaitingForPayment("recon-1");
        UUID paymentId = craftStalePending(order, true);

        env.awaitStatus(order.id(), "CONFIRMED", TIMEOUT); // o job de reconciliacao emitiu PaymentAuthorized
        assertThat(env.paymentDb().queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("AUTHORIZED");

        // religa o consumidor: o InventoryReserved pendente NAO pode cobrar de novo
        resumePaymentListener(payment);
        kafka.awaitLagZero(Duration.ofSeconds(90), "payment-service");
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class,
                order.id())).isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM gateway_charges WHERE idempotency_key = ?",
                Long.class, paymentId.toString())).isEqualTo(1L);
        assertThat(env.paymentDb().queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregateid = ? AND type = 'PaymentAuthorized'", Long.class,
                order.id().toString())).isEqualTo(1L);
        MeterRegistry meters = payment.ctx().getBean(MeterRegistry.class);
        assertThat(meters.get("payments.reconciled").tag("gateway_status", "AUTHORIZED").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("reconciliacao: gateway nunca viu a cobranca e o prazo estourou => DECLINED e a saga compensa")
    void reconcilerGivesUpAndSagaCompensates() {
        RunningApp payment = restartPayment("--payment.reconciliation.give-up-after=PT5S");
        pausePaymentListener(payment);
        Created order = orderWaitingForPayment("recon-2");
        UUID paymentId = craftStalePending(order, false);

        env.awaitStatus(order.id(), "CANCELLED", TIMEOUT);
        assertThat(env.paymentDb().queryForMap("SELECT status, failure_reason FROM payments WHERE id = ?", paymentId))
                .containsEntry("status", "DECLINED").containsEntry("failure_reason", "reconciliation_timeout");
        // compensacao: estoque liberado
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(env.inventoryDb().queryForList("SELECT status FROM reservations WHERE order_id = ?",
                        String.class, order.id())).containsExactly("RELEASED"));
    }

    @Test
    @DisplayName("gateway instavel (40% de HTTP 503): retry com backoff absorve as falhas e todos os pedidos confirmam")
    void retryAbsorbsTransientGatewayFailures() {
        // o breaker fica fora do cenario (limiar alto): aqui so o RETRY esta sob teste; o breaker tem teste proprio abaixo
        RunningApp payment = restartPayment("--payment.gateway.sim.failure-rate=0.4",
                "--resilience4j.circuitbreaker.instances.gateway.failure-rate-threshold=95");
        List<Created> orders = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            orders.add(env.createOrder("flaky-" + i, "P-006", 1));
        }
        for (Created o : orders) {
            env.awaitStatus(o.id(), "CONFIRMED", TIMEOUT);
        }
        // nenhuma cobranca em duplicidade, mesmo com retries
        String ids = orders.stream().map(o -> "'" + o.id() + "'").reduce((a, b) -> a + "," + b).orElseThrow();
        assertThat(env.paymentDb().queryForObject(
                "SELECT count(*) FROM gateway_charges g JOIN payments p ON p.id::text = g.idempotency_key "
                        + "WHERE g.status = 'AUTHORIZED' AND p.order_id IN (" + ids + ")", Long.class)).isEqualTo(25L);
        // houve retries de verdade
        MeterRegistry meters = payment.ctx().getBean(MeterRegistry.class);
        double retried = meters.find("resilience4j.retry.calls").tag("kind", "successful_with_retry").functionCounters()
                .stream().mapToDouble(c -> c.count()).sum();
        assertThat(retried).as("chamadas bem-sucedidas apos retry").isGreaterThan(0);
    }

    @Test
    @DisplayName("gateway fora do ar: circuit breaker ABRE; ao normalizar, reconciliacao desiste e a saga converge sem vazar reserva")
    void circuitBreakerOpensAndSystemConverges() {
        RunningApp broken = restartPayment("--payment.gateway.sim.failure-rate=1.0",
                "--payment.reconciliation.give-up-after=PT15S");
        List<Created> orders = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            orders.add(env.createOrder("outage-" + i, "P-006", 1));
        }
        CircuitBreakerRegistry breakers = broken.ctx().getBean(CircuitBreakerRegistry.class);
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100)).until(() ->
                breakers.circuitBreaker("gateway").getState() == CircuitBreaker.State.OPEN
                        || breakers.circuitBreaker("gateway").getState() == CircuitBreaker.State.HALF_OPEN);
        System.out.println("[T06] circuit breaker abriu: " + breakers.circuitBreaker("gateway").getMetrics());

        // gateway volta (nova instancia saudavel); pedidos pendentes sao resolvidos pela reconciliacao/reentrega
        restartPayment("--payment.reconciliation.give-up-after=PT15S");
        await().atMost(Duration.ofMinutes(4)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            for (Created o : orders) {
                String s = env.orderStatus(o.id());
                if (!s.equals("CONFIRMED") && !s.equals("CANCELLED")) {
                    return false;
                }
            }
            return true;
        });
        // invariante: pedido CANCELLED nunca deixa reserva ativa; CONFIRMED tem cobranca autorizada
        for (Created o : orders) {
            String status = env.orderStatus(o.id());
            if (status.equals("CANCELLED")) {
                await().atMost(TIMEOUT).untilAsserted(() ->
                        assertThat(env.inventoryDb().queryForList("SELECT status FROM reservations WHERE order_id = ?",
                                String.class, o.id())).isSubsetOf("RELEASED"));
            } else {
                assertThat(env.paymentDb().queryForObject("SELECT status FROM payments WHERE order_id = ?",
                        String.class, o.id())).isEqualTo("AUTHORIZED");
            }
        }
    }
}

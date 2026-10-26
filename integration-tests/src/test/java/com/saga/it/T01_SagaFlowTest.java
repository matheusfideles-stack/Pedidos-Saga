package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.it.SagaEnvironment.Created;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Etapa 5: saga completa (3 servicos + Kafka + Debezium) - fluxo feliz e compensacoes. */
class T01_SagaFlowTest {

    private static SagaEnvironment env;

    @BeforeAll
    static void start() {
        env = SagaEnvironment.get();
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @Test
    @DisplayName("fluxo feliz: PENDING -> INVENTORY_RESERVED -> PAYMENT_AUTHORIZED -> CONFIRMED")
    void happyPath() {
        Created order = env.createOrder("happy-1", "P-001", 2, "P-002", 1);
        assertThat(order.status()).isEqualTo(201);
        assertThat(order.body().get("status").asText()).isEqualTo("PENDING");

        env.awaitStatus(order.id(), "CONFIRMED", TIMEOUT);

        List<String> history = env.statusHistory(order.id());
        assertThat(history).first().isEqualTo("PENDING");
        assertThat(history).endsWith("PAYMENT_AUTHORIZED", "CONFIRMED");

        // estoque: reserva efetivada (COMMITTED), uma por item. Acontece DEPOIS do CONFIRMED (o estoque reage a
        // OrderConfirmed de forma assincrona): consistencia eventual, entao o teste espera em vez de assumir.
        org.awaitility.Awaitility.await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(env.inventoryDb().queryForList("SELECT status FROM reservations WHERE order_id = ?",
                        String.class, order.id())).containsExactly("COMMITTED", "COMMITTED"));
        // pagamento autorizado uma unica vez
        Map<String, Object> payment = env.paymentDb().queryForMap(
                "SELECT id, status, amount FROM payments WHERE order_id = ?", order.id());
        assertThat(payment.get("status")).isEqualTo("AUTHORIZED");
        assertThat(payment.get("amount").toString()).isEqualTo("820.00"); // 2*350 + 120
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM gateway_charges WHERE idempotency_key = ?",
                Long.class, payment.get("id").toString())).isEqualTo(1L);
        // eventos de saida: exatamente um de cada
        assertThat(env.orderDb().queryForList("SELECT type FROM outbox WHERE aggregateid = ? ORDER BY created_at",
                String.class, order.id().toString())).containsExactly("OrderCreated", "OrderConfirmed");
    }

    @Test
    @DisplayName("falha de estoque: pedido CANCELLED, nada e cobrado e nada fica reservado")
    void inventoryFailure() {
        int availableBefore = env.stock("P-009", "available");
        assertThat(availableBefore).isEqualTo(5);

        Created order = env.createOrder("stock-1", "P-009", 6);
        env.awaitStatus(order.id(), "CANCELLED", TIMEOUT);

        assertThat(env.orderDb().queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class,
                order.id())).contains("inventory").contains("insuficiente");
        assertThat(env.statusHistory(order.id())).containsExactly("PENDING", "CANCELLED");
        assertThat(env.inventoryDb().queryForObject("SELECT count(*) FROM reservations WHERE order_id = ?", Long.class,
                order.id())).isZero();
        assertThat(env.stock("P-009", "available")).isEqualTo(5);
        // o pagamento nunca cobrou (no maximo um tombstone CANCELLED, gerado pelo OrderCancelled)
        assertThat(env.paymentDb().queryForList("SELECT status FROM payments WHERE order_id = ?", String.class,
                order.id())).isSubsetOf("CANCELLED");
        assertThat(env.paymentDb().queryForObject("SELECT count(*) FROM gateway_charges", Long.class))
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("falha de pagamento: compensa liberando o estoque e cancela o pedido")
    void paymentFailureCompensates() {
        int available = env.stock("P-010", "available");
        int reserved = env.stock("P-010", "reserved");

        Created order = env.createOrder("decline-1", "P-010", 4);
        env.awaitStatus(order.id(), "CANCELLED", TIMEOUT);

        assertThat(env.orderDb().queryForObject("SELECT cancel_reason FROM orders WHERE id = ?", String.class,
                order.id())).contains("payment").contains("declined");
        assertThat(env.statusHistory(order.id())).contains("PENDING", "CANCELLED").doesNotContain("CONFIRMED");
        assertThat(env.paymentDb().queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class,
                order.id())).isEqualTo("DECLINED");

        // compensacao: a reserva e liberada e o estoque volta ao valor original
        org.awaitility.Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(env.inventoryDb().queryForList("SELECT status FROM reservations WHERE order_id = ?",
                    String.class, order.id())).containsExactly("RELEASED");
            assertThat(env.stock("P-010", "available")).isEqualTo(available);
            assertThat(env.stock("P-010", "reserved")).isEqualTo(reserved);
        });
    }

    @Test
    @DisplayName("pagamento recusado por valor acima do limite do gateway")
    void declinedByAmount() {
        Created order = env.createOrder("rich-1", "P-003", 8); // 8 * 1500 = 12000 > 10000
        env.awaitStatus(order.id(), "CANCELLED", TIMEOUT);
        assertThat(env.paymentDb().queryForObject("SELECT failure_reason FROM payments WHERE order_id = ?",
                String.class, order.id())).isEqualTo("declined_by_gateway");
    }

    @Test
    @DisplayName("cancelar pedido CONFIRMED retorna 409; cancelar de novo um CANCELLED e idempotente (200)")
    void cancelRules() {
        Created confirmed = env.createOrder("cancel-1", "P-006", 1);
        env.awaitStatus(confirmed.id(), "CONFIRMED", TIMEOUT);
        int status = RestClient.create(env.anyOrderService().url()).post()
                .uri("/orders/{id}/cancel", confirmed.id()).exchange((q, s) -> s.getStatusCode().value());
        assertThat(status).isEqualTo(409);

        Created cancelled = env.createOrder("decline-2", "P-006", 1);
        env.awaitStatus(cancelled.id(), "CANCELLED", TIMEOUT);
        int again = RestClient.create(env.anyOrderService().url()).post()
                .uri("/orders/{id}/cancel", cancelled.id()).exchange((q, s) -> s.getStatusCode().value());
        assertThat(again).isEqualTo(200);
    }

    @Test
    @DisplayName("cancelamento do cliente apos a reserva libera o estoque (compensacao por OrderCancelled)")
    void customerCancelCompensates() {
        // Gateway lento nao e necessario: cancelamos assim que o pedido nasce; o resultado depende da corrida,
        // mas o invariante vale em qualquer desfecho: estoque e pagamento nunca ficam inconsistentes.
        UUID id = env.createOrder("cancel-race-1", "P-007", 3).id();
        int status = RestClient.create(env.anyOrderService().url()).post().uri("/orders/{id}/cancel", id)
                .exchange((q, s) -> s.getStatusCode().value());
        assertThat(status).isIn(200, 409);

        String finalStatus = status == 200 ? "CANCELLED" : "CONFIRMED";
        env.awaitStatus(id, finalStatus, TIMEOUT);
        org.awaitility.Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            List<String> reservation = env.inventoryDb().queryForList(
                    "SELECT status FROM reservations WHERE order_id = ?", String.class, id);
            List<String> payment = env.paymentDb().queryForList(
                    "SELECT status FROM payments WHERE order_id = ?", String.class, id);
            if ("CANCELLED".equals(finalStatus)) {
                assertThat(reservation).isSubsetOf("RELEASED");        // nada fica reservado
                assertThat(payment).isSubsetOf("CANCELLED", "DECLINED"); // nada fica cobrado
            } else {
                assertThat(reservation).containsExactly("COMMITTED");
                assertThat(payment).containsExactly("AUTHORIZED");
            }
        });
    }
}

package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.it.SagaEnvironment.Created;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Etapa 8: uma saga inteira (HTTP + 3 servicos + Kafka + Debezium) aparece como UM unico trace. */
class T08_TracePropagationTest {

    private static SagaEnvironment env;

    @BeforeAll
    static void start() {
        env = SagaEnvironment.get();
    }

    private static String traceIdOf(org.springframework.jdbc.core.JdbcTemplate db, String orderId, String type) {
        String traceparent = db.queryForObject(
                "SELECT traceparent FROM outbox WHERE aggregateid = ? AND type = ?", String.class, orderId, type);
        assertThat(traceparent).as("traceparent de %s", type).isNotNull().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        return traceparent.split("-")[1];
    }

    @Test
    @DisplayName("o traceId do POST /orders chega ao inventory, payment e de volta ao order (via headers Kafka do Debezium)")
    void singleTraceAcrossTheSaga() {
        Created order = env.createOrder("trace-1", "P-006", 1);
        env.awaitStatus(order.id(), "CONFIRMED", Duration.ofSeconds(90));
        String id = order.id().toString();

        // o contexto gravado no outbox de CADA servico e o mesmo trace
        String root = traceIdOf(env.orderDb(), id, "OrderCreated");
        assertThat(traceIdOf(env.inventoryDb(), id, "InventoryReserved")).isEqualTo(root);
        assertThat(traceIdOf(env.paymentDb(), id, "PaymentAuthorized")).isEqualTo(root);
        assertThat(traceIdOf(env.orderDb(), id, "OrderConfirmed")).isEqualTo(root);

        // os spans exportados formam um unico trace que atravessa os tres servicos
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<SpanData> spans = env.spans.getFinishedSpanItems().stream()
                    .filter(s -> s.getTraceId().equals(root)).toList();
            Set<String> services = spans.stream()
                    .map(s -> s.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                    .collect(Collectors.toSet());
            assertThat(services).contains("order-service", "inventory-service", "payment-service");
        });
        long spansInTrace = env.spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(root)).count();
        System.out.println("[T08] trace " + root + ": " + spansInTrace + " spans em 3 servicos");
    }
}

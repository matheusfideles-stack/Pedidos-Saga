package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.it.SagaEnvironment.Created;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Etapa 8: logs estruturados em JSON com traceId, orderId e eventId (profile "json").
 * Roda por ultimo (ordem alfabetica) porque o perfil muda o formato de log da JVM de teste.
 */
class T09_JsonLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static SagaEnvironment env;
    private static PrintStream original;
    private static final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @BeforeAll
    static void restartServicesWithJsonProfile() {
        env = SagaEnvironment.get();
        original = System.out;
        System.setOut(new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                captured.write(b);
                original.write(b);
            }
        }, true, StandardCharsets.UTF_8));
        for (String service : List.of("order", "inventory", "payment")) {
            env.stopAll(service);
            env.start(service, "--spring.profiles.active=json");
        }
    }

    @AfterAll
    static void restore() {
        System.setOut(original);
    }

    private static List<JsonNode> jsonLines() {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : captured.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    nodes.add(JSON.readTree(line));
                } catch (Exception ignored) {
                    // linha parcial de outro appender
                }
            }
        }
        return nodes;
    }

    @Test
    @DisplayName("logs em JSON carregam traceId, orderId e eventId (correlacao com o trace da saga)")
    void logsAreStructuredWithCorrelationIds() {
        Created order = env.createOrder("json-log-1", "P-006", 1);
        env.awaitStatus(order.id(), "CONFIRMED", Duration.ofSeconds(90));
        String id = order.id().toString();

        // provoca um log com eventId: reentrega de um evento ja processado (duplicata descartada)
        KafkaProbe kafka = new KafkaProbe(env.infra.kafkaBootstrap());
        String payload = env.orderDb().queryForObject(
                "SELECT payload FROM outbox WHERE aggregateid = ? AND type = 'OrderCreated'", String.class, id);
        kafka.publish("order.events", id, payload, 1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<JsonNode> lines = jsonLines();
            assertThat(lines).isNotEmpty();
            // toda linha JSON tem timestamp e mensagem
            assertThat(lines).allSatisfy(n -> assertThat(n.has("@timestamp") && n.has("message")).isTrue());
            // linhas de consumidor trazem orderId + eventId; e ha linhas com traceId do mesmo trace
            List<JsonNode> forOrder = lines.stream().filter(n -> id.equals(n.path("orderId").asText(null))).toList();
            assertThat(forOrder).isNotEmpty();
            assertThat(forOrder).anySatisfy(n -> {
                assertThat(n.path("eventId").asText()).matches(UUID.randomUUID().toString().replaceAll("[0-9a-f]", "[0-9a-f]"));
                assertThat(n.path("traceId").asText()).hasSize(32);
            });
            assertThat(lines.stream().map(n -> n.path("message").asText()))
                    .anyMatch(m -> m.contains("duplicado"));
        });
    }
}

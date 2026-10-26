package com.saga.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.testsupport.TestInfra;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

class OrderApiIntegrationTest {

    private static ConfigurableApplicationContext ctx;
    private static RestClient http;
    private static JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Response(HttpStatusCode status, JsonNode body, String replayed) {
    }

    @BeforeAll
    static void start() {
        TestInfra infra = TestInfra.get();
        ctx = OrderServiceApplication.application()
                .run("--server.port=0",
                        "--spring.datasource.url=" + infra.createDatabase("orders_api_it"),
                        "--spring.datasource.username=" + infra.pgUser(),
                        "--spring.datasource.password=" + infra.pgPassword(),
                        "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                        "--spring.data.redis.port=" + infra.redisPort());
        http = RestClient.create("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port"));
        jdbc = ctx.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static Response post(String key, Object body) {
        return http.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (key != null) {
                        h.set("Idempotency-Key", key);
                    }
                })
                .body(body)
                .exchange((req, res) -> new Response(res.getStatusCode(), JSON.readTree(res.getBody()),
                        res.getHeaders().getFirst("Idempotent-Replayed")));
    }

    private static Map<String, Object> order(String customer, Object... productQty) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            items.add(Map.of("productId", productQty[i], "quantity", productQty[i + 1]));
        }
        return Map.of("customerId", customer, "items", items);
    }

    @Test
    @DisplayName("cria pedido PENDING e grava OrderCreated no outbox na mesma transacao")
    void createsOrderAndOutboxAtomically() {
        Response r = post(null, order("c-1", "P-001", 2, "P-006", 1));

        assertThat(r.status().value()).isEqualTo(201);
        assertThat(r.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(r.body().get("total").decimalValue()).isEqualByComparingTo("735.00"); // 2*350 + 35
        UUID id = UUID.fromString(r.body().get("id").asText());

        List<String> outbox = jdbc.queryForList(
                "SELECT type FROM outbox WHERE aggregateid = ? AND aggregatetype = 'order'", String.class, id.toString());
        assertThat(outbox).containsExactly("OrderCreated");
        String payload = jdbc.queryForObject("SELECT payload FROM outbox WHERE aggregateid = ?", String.class,
                id.toString());
        assertThat(payload).contains("\"version\":1").contains(id.toString()).contains("eventId");
    }

    @Test
    @DisplayName("consulta pedido e retorna 404 para inexistente")
    void getOrder() {
        UUID id = UUID.fromString(post(null, order("c-2", "P-002", 1)).body().get("id").asText());

        Response found = http.get().uri("/orders/{id}", id)
                .exchange((q, s) -> new Response(s.getStatusCode(), JSON.readTree(s.getBody()), null));
        assertThat(found.status().value()).isEqualTo(200);
        assertThat(found.body().get("items")).hasSize(1);

        Response missing = http.get().uri("/orders/{id}", UUID.randomUUID())
                .exchange((q, s) -> new Response(s.getStatusCode(), JSON.readTree(s.getBody()), null));
        assertThat(missing.status().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("produto inexistente: 422 e nada e gravado")
    void unknownProduct() {
        long before = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        Response r = post(null, order("c-3", "NAO-EXISTE", 1));
        assertThat(r.status().value()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("validacao: itens vazios retornam 400")
    void validation() {
        Response r = post(null, Map.of("customerId", "c", "items", List.of()));
        assertThat(r.status().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("Idempotency-Key: mesma chave + mesmo payload retorna a MESMA resposta e nao cria outro pedido")
    void idempotentReplay() {
        String key = UUID.randomUUID().toString();
        Response first = post(key, order("c-4", "P-001", 1));
        Response second = post(key, order("c-4", "P-001", 1));

        assertThat(first.status().value()).isEqualTo(201);
        assertThat(second.status().value()).isEqualTo(201);
        assertThat(second.replayed()).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = 'c-4'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Idempotency-Key: payload equivalente com itens reordenados/repetidos e o mesmo payload")
    void idempotencyIsCanonical() {
        String key = UUID.randomUUID().toString();
        Response first = post(key, order("c-5", "P-001", 1, "P-002", 2));
        Response second = post(key, order("c-5", "P-002", 1, "P-001", 1, "P-002", 1));
        assertThat(second.status().value()).isEqualTo(201);
        assertThat(second.body().get("id")).isEqualTo(first.body().get("id"));
    }

    @Test
    @DisplayName("Idempotency-Key: mesma chave com payload diferente retorna 422")
    void idempotencyKeyReusedWithDifferentPayload() {
        String key = UUID.randomUUID().toString();
        assertThat(post(key, order("c-6", "P-001", 1)).status().value()).isEqualTo(201);

        Response conflict = post(key, order("c-6", "P-001", 2));
        assertThat(conflict.status().value()).isEqualTo(422);
        assertThat(conflict.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = 'c-6'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Idempotency-Key: 50 requisicoes concorrentes com a mesma chave criam UM pedido")
    void concurrentSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Callable<Response> call = () -> post(key, order("c-7", "P-003", 1));
                futures.add(pool.submit(call));
            }
            List<String> ids = new ArrayList<>();
            for (Future<Response> f : futures) {
                Response r = f.get();
                assertThat(r.status().value()).isEqualTo(201);
                ids.add(r.body().get("id").asText());
            }
            assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = 'c-7'", Long.class))
                    .isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregateid = ?", Long.class, ids.get(0)))
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("cancelamento move PENDING -> CANCELLED, emite OrderCancelled e e idempotente")
    void cancel() {
        UUID id = UUID.fromString(post(null, order("c-8", "P-004", 1)).body().get("id").asText());

        for (int i = 0; i < 2; i++) { // segunda chamada: no-op
            Response r = http.post().uri("/orders/{id}/cancel", id)
                    .exchange((q, s) -> new Response(s.getStatusCode(), JSON.readTree(s.getBody()), null));
            assertThat(r.status().value()).isEqualTo(200);
            assertThat(r.body().get("status").asText()).isEqualTo("CANCELLED");
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT type FROM outbox WHERE aggregateid = ? ORDER BY created_at",
                        String.class, id.toString())).containsExactly("OrderCreated", "OrderCancelled"));
    }
}

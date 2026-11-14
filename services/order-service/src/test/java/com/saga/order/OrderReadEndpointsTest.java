package com.saga.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.testsupport.TestInfra;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Endpoints de leitura usados pela interface: listagem de pedidos, linha do tempo e catalogo.
 * O servico roda sem inventory e payment, entao os pedidos ficam em PENDING e o teste e deterministico.
 */
class OrderReadEndpointsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static ConfigurableApplicationContext ctx;
    private static RestClient http;

    private record Result(HttpStatusCode status, JsonNode body) {
    }

    @BeforeAll
    static void start() {
        TestInfra infra = TestInfra.get();
        ctx = OrderServiceApplication.application()
                .run("--server.port=0",
                        "--spring.datasource.url=" + infra.createDatabase("orders_read_it"),
                        "--spring.datasource.username=" + infra.pgUser(),
                        "--spring.datasource.password=" + infra.pgPassword(),
                        "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                        "--spring.data.redis.port=" + infra.redisPort());
        http = RestClient.create("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static Result get(String uri, Object... vars) {
        return http.get().uri(uri, vars)
                .exchange((req, res) -> new Result(res.getStatusCode(), JSON.readTree(res.getBody())));
    }

    private static Result post(String uri, Object body) {
        return http.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body)
                .exchange((req, res) -> new Result(res.getStatusCode(), JSON.readTree(res.getBody())));
    }

    private static String createOrder(String customer) {
        Result r = post("/orders", Map.of("customerId", customer,
                "items", List.of(Map.of("productId", "P-001", "quantity", 1))));
        return r.body().get("id").asText();
    }

    @Test
    @DisplayName("GET /orders lista os pedidos mais recentes primeiro e respeita o limite")
    void listsRecentOrdersFirst() {
        String older = createOrder("list-older");
        String newer = createOrder("list-newer");

        Result all = get("/orders?limit=200");
        assertThat(all.status().value()).isEqualTo(200);
        List<String> ids = new java.util.ArrayList<>();
        all.body().forEach(o -> ids.add(o.get("id").asText()));
        assertThat(ids.indexOf(newer)).isLessThan(ids.indexOf(older));

        Result one = get("/orders?limit=1");
        assertThat(one.body()).hasSize(1);
    }

    @Test
    @DisplayName("GET /orders/{id}/history mostra a criacao e o cancelamento, em ordem")
    void historyShowsTransitionsInOrder() {
        String id = createOrder("history-1");
        post("/orders/" + id + "/cancel", null);

        Result h = get("/orders/{id}/history", id);
        assertThat(h.status().value()).isEqualTo(200);
        assertThat(h.body().get(0).get("from").isNull()).isTrue();
        assertThat(h.body().get(0).get("to").asText()).isEqualTo("PENDING");
        assertThat(h.body().get(h.body().size() - 1).get("to").asText()).isEqualTo("CANCELLED");
        assertThat(h.body().get(h.body().size() - 1).get("trigger").asText()).isEqualTo("CUSTOMER_CANCEL");
    }

    @Test
    @DisplayName("GET /orders/{id}/history de pedido inexistente retorna 404")
    void historyOfUnknownOrderIs404() {
        Result r = get("/orders/{id}/history", java.util.UUID.randomUUID());
        assertThat(r.status().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /catalog/products lista os produtos ativos do seed")
    void catalogListsActiveProducts() {
        Result r = get("/catalog/products");
        assertThat(r.status().value()).isEqualTo(200);
        List<String> codes = new java.util.ArrayList<>();
        r.body().forEach(p -> codes.add(p.get("id").asText()));
        assertThat(codes).contains("P-001", "P-009");
        assertThat(codes).isSorted();
    }
}

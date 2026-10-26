package com.saga.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.testsupport.TestInfra;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Etapa 3: a linha gravada no outbox chega ao Kafka via Debezium (CDC), roteada e particionada por orderId. */
class OutboxCdcTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static TestInfra infra;
    private static ConfigurableApplicationContext ctx;
    private static RestClient http;

    @BeforeAll
    static void start() {
        infra = TestInfra.get();
        String db = "orders_cdc_it";
        ctx = OrderServiceApplication.application().run("--server.port=0",
                "--spring.datasource.url=" + infra.createDatabase(db),
                "--spring.datasource.username=" + infra.pgUser(),
                "--spring.datasource.password=" + infra.pgPassword(),
                "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                "--spring.data.redis.port=" + infra.redisPort());
        http = RestClient.create("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port"));
        infra.startCdc(db); // depois do Flyway: a tabela outbox ja existe
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    private static String createOrder(String customer, int qty) {
        JsonNode body = http.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("customerId", customer, "items", List.of(Map.of("productId", "P-002", "quantity", qty))))
                .exchange((q, s) -> JSON.readTree(s.getBody()));
        return body.get("id").asText();
    }

    private static List<ConsumerRecord<String, String>> consume(String topic, Set<String> keys, Duration timeout) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, infra.kafkaBootstrap());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline && found.size() < keys.size()) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    if (keys.contains(r.key())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("OrderCreated do outbox e publicado em order.events pelo Debezium, com chave = orderId")
    void outboxRowIsPublishedByDebezium() throws Exception {
        String id = createOrder("cdc-1", 3);

        List<ConsumerRecord<String, String>> records = consume("order.events", Set.of(id), Duration.ofSeconds(60));

        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> rec = records.get(0);
        assertThat(rec.key()).isEqualTo(id);
        JsonNode env = JSON.readTree(rec.value());
        assertThat(env.get("type").asText()).isEqualTo("OrderCreated");
        assertThat(env.get("version").asInt()).isEqualTo(1);
        assertThat(env.get("aggregateId").asText()).isEqualTo(id);
        assertThat(env.get("data").get("orderId").asText()).isEqualTo(id);
        assertThat(env.get("data").get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        // headers colocados pelo EventRouter a partir das colunas do outbox
        assertThat(new String(rec.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("OrderCreated");
        assertThat(rec.headers().lastHeader("id")).isNotNull();
    }

    @Test
    @DisplayName("todos os eventos do mesmo pedido vao para a mesma particao (ordem por orderId preservada)")
    void sameOrderSamePartitionAndOrdered() throws Exception {
        String id = createOrder("cdc-2", 1);
        http.post().uri("/orders/{id}/cancel", id).retrieve().toBodilessEntity();

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 60_000;
        while (records.size() < 2 && System.currentTimeMillis() < deadline) {
            records = consume("order.events", Set.of(id), Duration.ofSeconds(3));
            // consume() para quando encontra keys.size() (=1); repete ate ver os dois
            if (records.size() < 2) {
                records = consumeAll(id);
            }
        }
        assertThat(records).hasSize(2);
        assertThat(records.stream().map(ConsumerRecord::partition).distinct()).hasSize(1);
        assertThat(records.stream().map(r -> {
            try {
                return JSON.readTree(r.value()).get("type").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).containsExactly("OrderCreated", "OrderCancelled");
    }

    private static List<ConsumerRecord<String, String>> consumeAll(String key) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, infra.kafkaBootstrap());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of("order.events"));
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }
}

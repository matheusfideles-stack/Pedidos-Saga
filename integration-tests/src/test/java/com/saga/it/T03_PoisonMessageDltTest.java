package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.it.SagaEnvironment.Created;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Mensagem envenenada vai para a DLT sem travar a particao. */
class T03_PoisonMessageDltTest {

    private static SagaEnvironment env;
    private static KafkaProbe kafka;

    @BeforeAll
    static void start() {
        env = SagaEnvironment.get();
        kafka = new KafkaProbe(env.infra.kafkaBootstrap());
    }

    @Test
    @DisplayName("JSON invalido em order.events: cada consumidor envia para order.events.DLT e a saga segue funcionando")
    void poisonMessageGoesToDltAndPartitionKeepsFlowing() {
        String key = UUID.randomUUID().toString();
        kafka.publish("order.events", key, "isto-nao-e-json{{{", 1);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<ConsumerRecord<String, String>> dlt = kafka.readAll("order.events.DLT", key, Duration.ofSeconds(2));
            // inventory-service e payment-service consomem order.events: uma copia na DLT de cada um
            assertThat(dlt).hasSize(2);
            assertThat(dlt.get(0).value()).isEqualTo("isto-nao-e-json{{{");
            assertThat(dlt.get(0).headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
        });

        // a particao nao ficou bloqueada: um pedido novo completa normalmente
        Created order = env.createOrder("after-poison", "P-008", 1);
        env.awaitStatus(order.id(), "CONFIRMED", Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("envelope valido com dados invalidos (orderId nao-UUID) tambem vai direto para a DLT")
    void invalidDataGoesToDlt() {
        String key = UUID.randomUUID().toString();
        String bad = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"InventoryReserved\",\"version\":1,"
                + "\"aggregateId\":\"" + key + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"data\":{\"orderId\":\"nao-e-uuid\",\"amount\":10}}";
        kafka.publish("inventory.events", key, bad, 1);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(kafka.readAll("inventory.events.DLT", key, Duration.ofSeconds(2))).isNotEmpty());
    }
}

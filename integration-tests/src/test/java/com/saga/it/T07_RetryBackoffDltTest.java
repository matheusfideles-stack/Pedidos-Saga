package com.saga.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.common.kafka.KafkaErrorHandlingConfig;
import com.saga.testsupport.TestInfra;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Retry com backoff EXPONENCIAL + JITTER e DLT, usando o mesmo tratamento de erro dos servicos
 * (common/KafkaErrorHandlingConfig) em um listener de teste que falha de forma controlada.
 */
class T07_RetryBackoffDltTest {

    static final Map<String, List<Long>> attempts = new ConcurrentHashMap<>();
    static final List<String> processed = new CopyOnWriteArrayList<>();

    @Configuration
    @EnableKafka
    @ImportAutoConfiguration({KafkaAutoConfiguration.class, MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class})
    @Import(KafkaErrorHandlingConfig.class)
    static class TestApp {

        @Bean
        NewTopic topic() {
            return TopicBuilder.name("retry-test").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic dlt() {
            return TopicBuilder.name("retry-test.DLT").partitions(1).replicas(1).build();
        }

        @Bean
        FlakyListener flakyListener() {
            return new FlakyListener();
        }
    }

    static class FlakyListener {
        @KafkaListener(id = "flaky", topics = "retry-test", groupId = "retry-it")
        void on(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> rec) {
            List<Long> times = attempts.computeIfAbsent(rec.key(), k -> new CopyOnWriteArrayList<>());
            times.add(System.currentTimeMillis());
            if (rec.key().equals("always-fails") || (rec.key().equals("recovers") && times.size() < 3)) {
                throw new IllegalStateException("falha transitoria simulada #" + times.size());
            }
            processed.add(rec.key());
        }
    }

    private static ConfigurableApplicationContext ctx;
    private static KafkaProbe kafka;

    @BeforeAll
    static void start() {
        TestInfra infra = TestInfra.get();
        kafka = new KafkaProbe(infra.kafkaBootstrap());
        ctx = new SpringApplicationBuilder(TestApp.class).web(WebApplicationType.NONE).run(
                "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                "--spring.kafka.consumer.auto-offset-reset=earliest",
                "--spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "--spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "--spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "--spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "--saga.kafka.retry.max-retries=4",
                "--saga.kafka.retry.initial-interval=200ms",
                "--saga.kafka.retry.multiplier=2.0",
                "--saga.kafka.retry.max-interval=1s",
                "--saga.kafka.retry.jitter=100ms");
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    @Test
    @DisplayName("falha 2x e recupera na 3a tentativa: processa uma vez e NAO vai para a DLT")
    void transientFailureRecovers() {
        kafka.publish("retry-test", "recovers", "{}", 1);
        await().atMost(Duration.ofSeconds(30)).until(() -> processed.contains("recovers"));
        assertThat(attempts.get("recovers")).hasSize(3);
        assertThat(kafka.readAll("retry-test.DLT", "recovers", Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    @DisplayName("falha sempre: 1 + 4 tentativas com intervalos exponenciais (200, 400, 800, 1000ms + jitter) e depois DLT")
    void exhaustedRetriesGoToDlt() {
        kafka.publish("retry-test", "always-fails", "{\"x\":1}", 1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(kafka.readAll("retry-test.DLT", "always-fails", Duration.ofSeconds(2))).hasSize(1));

        List<Long> t = attempts.get("always-fails");
        assertThat(t).hasSize(5); // entrega original + 4 retries
        long[] floors = {200, 400, 800, 1000};      // min(max=1000, 200 * 2^n)
        for (int i = 0; i < 4; i++) {
            long gap = t.get(i + 1) - t.get(i);
            // intervalo >= base; e <= base + jitter(100ms) + folga de agendamento
            assertThat(gap).as("intervalo antes da tentativa %d", i + 2)
                    .isGreaterThanOrEqualTo(floors[i] - 20).isLessThan(floors[i] + 100 + 1500);
        }
        // exponencial: o 3o intervalo e bem maior que o 1o
        assertThat(t.get(3) - t.get(2)).isGreaterThan(t.get(1) - t.get(0));
    }
}

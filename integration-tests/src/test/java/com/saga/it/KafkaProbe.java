package com.saga.it;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Utilitarios de teste sobre o Kafka: publicar, ler e esperar os consumidores zerarem o lag. */
public final class KafkaProbe {

    private final String bootstrap;

    public KafkaProbe(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public void publish(String topic, String key, String value, int times) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            for (int i = 0; i < times; i++) {
                producer.send(new ProducerRecord<>(topic, key, value));
            }
            producer.flush();
        }
    }

    /** Le todo o topico (do inicio) e devolve os registros da chave (ou todos, se key == null). */
    public List<ConsumerRecord<String, String>> readAll(String topic, String key, Duration quietFor) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long lastData = System.currentTimeMillis();
            long start = lastData;
            while (System.currentTimeMillis() - lastData < quietFor.toMillis()
                    && System.currentTimeMillis() - start < 60_000) {
                var records = c.poll(Duration.ofMillis(300));
                if (!records.isEmpty()) {
                    lastData = System.currentTimeMillis();
                }
                for (ConsumerRecord<String, String> r : records) {
                    if (key == null || key.equals(r.key())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    /** Espera os grupos consumirem tudo que existe nos topicos (lag 0 em todas as particoes). */
    public void awaitLagZero(Duration timeout, String... groups) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(500)).alias("lag zero em " + List.of(groups))
                .until(() -> totalLag(groups) == 0);
    }

    public long totalLag(String... groups) throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        long lag = 0;
        try (AdminClient admin = AdminClient.create(p)) {
            for (String group : groups) {
                Map<TopicPartition, OffsetAndMetadata> committed =
                        admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
                Map<TopicPartition, OffsetSpec> latestSpec = new HashMap<>();
                committed.keySet().forEach(tp -> latestSpec.put(tp, OffsetSpec.latest()));
                var ends = admin.listOffsets(latestSpec).all().get();
                for (var e : committed.entrySet()) {
                    lag += Math.max(0, ends.get(e.getKey()).offset() - e.getValue().offset());
                }
            }
        }
        return lag;
    }

    public Set<String> topics() throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        try (AdminClient admin = AdminClient.create(p)) {
            return admin.listTopics().names().get();
        }
    }
}

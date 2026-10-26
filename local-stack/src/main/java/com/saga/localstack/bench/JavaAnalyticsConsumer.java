package com.saga.localstack.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Consumidor Java EQUIVALENTE ao analytics-worker em Go (mesmo algoritmo, mesmas tabelas, mesmo tamanho de lote):
 * poll -> parse -> UMA transacao (registra eventIds + upsert das janelas de 1 minuto) -> commit do offset.
 * Existe para a comparacao de throughput do README; usa kafka-clients e JDBC puros.
 */
public final class JavaAnalyticsConsumer implements Runnable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String bootstrap;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String topic;
    private final String group;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public JavaAnalyticsConsumer(String bootstrap, String jdbcUrl, String user, String password, String topic,
                                 String group) {
        this.bootstrap = bootstrap;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.topic = topic;
        this.group = group;
    }

    public void stop() {
        running.set(false);
    }

    public static void migrate(String jdbcUrl, String user, String password) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS analytics_processed_events (event_id UUID PRIMARY KEY, "
                    + "processed_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            s.execute("CREATE TABLE IF NOT EXISTS order_window_stats (window_start TIMESTAMPTZ NOT NULL, "
                    + "metric TEXT NOT NULL, event_count BIGINT NOT NULL DEFAULT 0, amount_cents BIGINT NOT NULL "
                    + "DEFAULT 0, PRIMARY KEY (window_start, metric))");
        }
    }

    private record Parsed(String eventId, String metric, Instant window, long amountCents) {
    }

    @Override
    public void run() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        p.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
             Connection db = DriverManager.getConnection(jdbcUrl, user, password)) {
            db.setAutoCommit(false);
            consumer.subscribe(List.of(topic));
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty()) {
                    continue;
                }
                List<Parsed> parsed = new ArrayList<>(records.count());
                for (ConsumerRecord<String, String> r : records) {
                    Parsed e = parse(r.value());
                    if (e != null) {
                        parsed.add(e);
                    }
                }
                apply(db, parsed);          // 1 transacao
                consumer.commitSync();      // offset SO depois do commit no banco
            }
        } catch (Exception e) {
            if (running.get()) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Parsed parse(String value) {
        try {
            JsonNode env = JSON.readTree(value);
            String type = env.path("type").asText();
            String metric = switch (type) {
                case "OrderCreated" -> "orders_created";
                case "OrderConfirmed" -> "orders_confirmed";
                case "OrderCancelled" -> "orders_cancelled";
                default -> null;
            };
            if (metric == null) {
                return null;
            }
            Instant at = Instant.parse(env.path("occurredAt").asText()).truncatedTo(ChronoUnit.MINUTES);
            long cents = "OrderCreated".equals(type) ? Math.round(env.path("data").path("total").asDouble() * 100) : 0;
            return new Parsed(env.path("eventId").asText(), metric, at, cents);
        } catch (Exception e) {
            return null; // invalida: ignorada (offset avanca)
        }
    }

    private static void apply(Connection db, List<Parsed> events) throws Exception {
        if (events.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        List<Parsed> unique = new ArrayList<>();
        for (Parsed e : events) {
            if (seen.add(e.eventId())) {
                unique.add(e);
            }
        }
        Set<String> fresh = new HashSet<>();
        Array ids = db.createArrayOf("uuid", unique.stream().map(Parsed::eventId).toArray());
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO analytics_processed_events (event_id) "
                + "SELECT unnest(?::uuid[]) ON CONFLICT DO NOTHING RETURNING event_id::text")) {
            ps.setArray(1, ids);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fresh.add(rs.getString(1));
                }
            }
        }
        record Key(Instant window, String metric) {
        }
        Map<Key, long[]> agg = new TreeMap<>((a, b) -> {
            int c = a.window().compareTo(b.window());
            return c != 0 ? c : a.metric().compareTo(b.metric());
        });
        for (Parsed e : unique) {
            if (fresh.contains(e.eventId())) {
                long[] d = agg.computeIfAbsent(new Key(e.window(), e.metric()), k -> new long[2]);
                d[0]++;
                d[1] += e.amountCents();
            }
        }
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO order_window_stats (window_start, metric, "
                + "event_count, amount_cents) VALUES (?, ?, ?, ?) ON CONFLICT (window_start, metric) DO UPDATE SET "
                + "event_count = order_window_stats.event_count + EXCLUDED.event_count, "
                + "amount_cents = order_window_stats.amount_cents + EXCLUDED.amount_cents")) {
            for (Map.Entry<Key, long[]> e : agg.entrySet()) {
                ps.setTimestamp(1, Timestamp.from(e.getKey().window()));
                ps.setString(2, e.getKey().metric());
                ps.setLong(3, e.getValue()[0]);
                ps.setLong(4, e.getValue()[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        db.commit();
    }
}

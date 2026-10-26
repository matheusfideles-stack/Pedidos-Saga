package com.saga.it;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.inventory.InventoryServiceApplication;
import com.saga.order.OrderServiceApplication;
import com.saga.payment.PaymentServiceApplication;
import com.saga.testsupport.TestInfra;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/**
 * Ambiente unico por JVM: PostgreSQL (3 bancos), Kafka, Debezium CDC e os tres servicos (em-processo).
 * Topicos e consumer groups sao globais por design, entao os testes COMPARTILHAM este ambiente
 * (sequencialmente) e isolam-se por pedido/cliente.
 */
public final class SagaEnvironment {

    public static final String ORDERS_DB = "orders";
    public static final String INVENTORY_DB = "inventory";
    public static final String PAYMENTS_DB = "payments";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static SagaEnvironment instance;

    public static synchronized SagaEnvironment get() {
        if (instance == null) {
            instance = new SagaEnvironment();
        }
        return instance;
    }

    /** Um servico em execucao (contexto Spring em-processo). */
    public record RunningApp(String service, String name, ConfigurableApplicationContext ctx, int port) {
        public String url() {
            return "http://localhost:" + port;
        }
    }

    public final TestInfra infra = TestInfra.get();
    public final ConcurrencyProbe.Shared probe = new ConcurrencyProbe.Shared();
    /** Todos os servicos exportam seus spans para este exporter em memoria (permite inspecionar o trace da saga). */
    public final io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter spans =
            io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
    private final List<RunningApp> apps = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final JdbcTemplate orderDb;
    private final JdbcTemplate inventoryDb;
    private final JdbcTemplate paymentDb;

    private SagaEnvironment() {
        orderDb = jdbc(ORDERS_DB);
        inventoryDb = jdbc(INVENTORY_DB);
        paymentDb = jdbc(PAYMENTS_DB);
        // Os servicos (Flyway) criam as tabelas; o CDC precisa da tabela outbox, entao sobe depois.
        start("order");
        start("inventory");
        start("payment");
        infra.startCdc(ORDERS_DB);
        infra.startCdc(INVENTORY_DB);
        infra.startCdc(PAYMENTS_DB);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> apps.forEach(a -> {
            try {
                a.ctx().close();
            } catch (Exception ignored) {
                // melhor esforco
            }
        })));
    }

    private JdbcTemplate jdbc(String db) {
        infra.createDatabase(db);
        return new JdbcTemplate(new DriverManagerDataSource(infra.jdbcUrl(db), infra.pgUser(), infra.pgPassword()));
    }

    public JdbcTemplate orderDb() {
        return orderDb;
    }

    public JdbcTemplate inventoryDb() {
        return inventoryDb;
    }

    public JdbcTemplate paymentDb() {
        return paymentDb;
    }

    // ------------------------------------------------------------------ ciclo de vida dos servicos

    /** Argumentos comuns: banco, Kafka e backoff curto para testes rapidos. */
    public List<String> commonArgs(String service) {
        String db = switch (service) {
            case "order" -> ORDERS_DB;
            case "inventory" -> INVENTORY_DB;
            default -> PAYMENTS_DB;
        };
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + infra.jdbcUrl(db),
                "--spring.datasource.username=" + infra.pgUser(),
                "--spring.datasource.password=" + infra.pgPassword(),
                "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                "--spring.kafka.consumer.properties.session.timeout.ms=10000",
                "--spring.kafka.consumer.properties.heartbeat.interval.ms=2000",
                "--saga.kafka.retry.max-retries=4",
                "--saga.kafka.retry.initial-interval=200ms",
                "--saga.kafka.retry.max-interval=1s",
                "--saga.kafka.retry.jitter=100ms"));
        if (service.equals("order")) {
            args.add("--spring.data.redis.port=" + infra.redisPort());
        }
        if (service.equals("payment")) {
            args.addAll(List.of("--payment.reconciliation.interval=PT2S", "--payment.reconciliation.stale-after=PT6S"));
        }
        return args;
    }

    public RunningApp start(String service, String... extraArgs) {
        List<String> args = commonArgs(service);
        args.addAll(List.of(extraArgs));
        String name = service + "-" + sequence.incrementAndGet();
        SpringApplicationBuilder builder = switch (service) {
            case "order" -> OrderServiceApplication.application();
            case "inventory" -> InventoryServiceApplication.application();
            case "payment" -> PaymentServiceApplication.application();
            default -> throw new IllegalArgumentException(service);
        };
        ConfigurableApplicationContext ctx = builder
                .initializers(c -> {
                    c.getBeanFactory().registerSingleton("concurrencyProbe", new ConcurrencyProbe(probe, name));
                    c.getBeanFactory().registerSingleton("inMemorySpanExporter", new NonClosingSpanExporter(spans));
                })
                .run(args.toArray(String[]::new));
        int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
        RunningApp app = new RunningApp(service, name, ctx, port);
        apps.add(app);
        return app;
    }

    public void stop(RunningApp app) {
        apps.remove(app);
        app.ctx().close();
    }

    public List<RunningApp> running(String service) {
        return apps.stream().filter(a -> a.service().equals(service)).toList();
    }

    public void stopAll(String service) {
        running(service).forEach(this::stop);
    }

    // ------------------------------------------------------------------ helpers de API

    public RunningApp anyOrderService() {
        List<RunningApp> list = running("order");
        return list.get(Math.floorMod(roundRobin.getAndIncrement(), list.size()));
    }

    public record Created(UUID id, JsonNode body, int status) {
    }

    public Created createOrder(String customerId, Object... productQty) {
        return createOrderIdem(null, customerId, productQty);
    }

    public Created createOrderIdem(String idempotencyKey, String customerId, Object... productQty) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            items.add(Map.of("productId", productQty[i], "quantity", productQty[i + 1]));
        }
        return RestClient.create(anyOrderService().url()).post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (idempotencyKey != null) {
                        h.set("Idempotency-Key", idempotencyKey);
                    }
                })
                .body(Map.of("customerId", customerId, "items", items))
                .exchange((q, s) -> {
                    JsonNode body = JSON.readTree(s.getBody());
                    return new Created(body.has("id") ? UUID.fromString(body.get("id").asText()) : null, body,
                            s.getStatusCode().value());
                });
    }

    public String orderStatus(UUID id) {
        return orderDb.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    public void awaitStatus(UUID id, String expected, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200))
                .alias("pedido " + id + " -> " + expected)
                .until(() -> expected.equals(orderStatus(id)));
    }

    public List<String> statusHistory(UUID id) {
        return orderDb.queryForList("SELECT to_status FROM order_status_history WHERE order_id = ? ORDER BY id",
                String.class, id);
    }

    public int stock(String productId, String column) {
        return inventoryDb.queryForObject("SELECT " + column + " FROM stock WHERE product_id = ?", Integer.class,
                productId);
    }

    public static File javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/java" + (System.getProperty("os.name").toLowerCase()
                .contains("win") ? ".exe" : ""));
    }
}

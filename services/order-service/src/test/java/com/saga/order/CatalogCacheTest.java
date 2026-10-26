package com.saga.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.saga.order.catalog.CatalogRepository;
import com.saga.order.catalog.ProductCache;
import com.saga.testsupport.TestInfra;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Etapa 7: cache-aside L1 (Caffeine) + L2 (Redis), TTL com jitter, anti-stampede e invalidacao por evento. */
class CatalogCacheTest {

    private static TestInfra infra;
    private static ConfigurableApplicationContext a;
    private static ConfigurableApplicationContext b;
    private static ProductCache cacheA;
    private static ProductCache cacheB;
    private static CatalogRepository repo;

    private static ConfigurableApplicationContext start(String db, int redisPort, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0",
                "--spring.datasource.url=" + infra.jdbcUrl(db),
                "--spring.datasource.username=" + infra.pgUser(),
                "--spring.datasource.password=" + infra.pgPassword(),
                "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
                "--spring.data.redis.port=" + redisPort,
                "--catalog.cache.l1-ttl=10m")); // L1 longo: prova que a invalidacao e por EVENTO, nao por TTL
        args.addAll(List.of(extra));
        return OrderServiceApplication.application().run(args.toArray(String[]::new));
    }

    @BeforeAll
    static void startInstances() {
        infra = TestInfra.get();
        String db = "orders_cache_it";
        infra.createDatabase(db);
        a = start(db, infra.redisPort());
        b = start(db, infra.redisPort());
        infra.startCdc(db);
        cacheA = a.getBean(ProductCache.class);
        cacheB = b.getBean(ProductCache.class);
        repo = a.getBean(CatalogRepository.class);
        // o listener de invalidacao le do FIM do topico: eventos publicados antes de ele ter particoes atribuidas
        // se perdem (o TTL limita a defasagem, ADR-004). Espera a atribuicao para o teste ser deterministico.
        for (ConfigurableApplicationContext ctx : List.of(a, b)) {
            org.springframework.kafka.listener.MessageListenerContainer c = ctx
                    .getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("catalog-cache-invalidation");
            await().atMost(Duration.ofSeconds(60)).until(() -> c.getAssignedPartitions() != null
                    && !c.getAssignedPartitions().isEmpty());
        }
    }

    @AfterAll
    static void stop() {
        a.close();
        b.close();
    }

    @Test
    @DisplayName("cache-aside: 1a leitura vai ao banco; 2a e L1; outra instancia ve o Redis (L2) sem tocar no banco")
    void layersAreUsedInOrder() {
        ProductCache.Stats a0 = cacheA.stats();
        assertThat(cacheA.get("P-003")).isPresent();
        assertThat(cacheA.stats().dbLoads() - a0.dbLoads()).isEqualTo(1);
        assertThat(cacheA.stats().l2Misses() - a0.l2Misses()).isEqualTo(1);

        cacheA.get("P-003");
        assertThat(cacheA.stats().l1Hits() - a0.l1Hits()).isEqualTo(1);
        assertThat(cacheA.stats().dbLoads() - a0.dbLoads()).isEqualTo(1); // continua 1

        ProductCache.Stats b0 = cacheB.stats();
        assertThat(cacheB.get("P-003")).isPresent();      // instancia B: L1 frio, L2 quente
        assertThat(cacheB.stats().l2Hits() - b0.l2Hits()).isEqualTo(1);
        assertThat(cacheB.stats().dbLoads() - b0.dbLoads()).isZero();

        MeterRegistry meters = a.getBean(MeterRegistry.class);
        assertThat(meters.get("catalog.cache.hit.ratio").tag("layer", "l1").gauge().value()).isGreaterThan(0);
        assertThat(meters.get("catalog.cache.requests").tag("layer", "l1").tag("result", "hit").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("produto inexistente tambem e cacheado (cache negativo) e nao martela o banco")
    void negativeCaching() {
        long before = cacheA.stats().dbLoads();
        for (int i = 0; i < 20; i++) {
            assertThat(cacheA.get("NAO-EXISTE-" + 1)).isEmpty();
        }
        assertThat(cacheA.stats().dbLoads() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("anti-stampede (1 instancia): 300 threads na mesma chave fria => UMA carga no banco")
    void stampedeSingleInstance() throws Exception {
        repo.save("P-STAMPEDE-1", "Produto frio 1", new BigDecimal("10.00"), true);
        long before = cacheA.stats().dbLoads();
        runConcurrently(300, () -> cacheA.get("P-STAMPEDE-1"));
        assertThat(cacheA.stats().dbLoads() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("anti-stampede (2 instancias): lock distribuido no Redis => UMA carga no banco para as duas")
    void stampedeAcrossInstances() throws Exception {
        repo.save("P-STAMPEDE-2", "Produto frio 2", new BigDecimal("10.00"), true);
        long before = cacheA.stats().dbLoads() + cacheB.stats().dbLoads();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ProductCache target = i % 2 == 0 ? cacheA : cacheB;
            futures.add(pool.submit(() -> {
                go.await();
                return target.get("P-STAMPEDE-2");
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long loads = cacheA.stats().dbLoads() + cacheB.stats().dbLoads() - before;
        System.out.println("[cache] cargas no banco com 200 leituras simultaneas em 2 instancias: " + loads);
        assertThat(loads).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL com jitter: 60 chaves populadas juntas expiram em instantes diferentes, dentro de +-20%")
    void ttlJitter() {
        StringRedisTemplate redis = a.getBean(StringRedisTemplate.class);
        List<Long> ttls = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            repo.save("P-JIT-" + i, "Jitter " + i, BigDecimal.ONE, true);
            cacheA.get("P-JIT-" + i);
            Long ttl = redis.getExpire("catalog:product:P-JIT-" + i, TimeUnit.MILLISECONDS);
            ttls.add(ttl);
        }
        long base = Duration.ofMinutes(5).toMillis();
        assertThat(ttls).allSatisfy(t -> assertThat(t).isBetween((long) (base * 0.8) - 2000, (long) (base * 1.2)));
        assertThat(ttls.stream().distinct().count()).as("TTLs distintos").isGreaterThan(30);
    }

    @Test
    @DisplayName("invalidacao por evento: PUT do catalogo -> outbox -> Debezium -> Kafka -> L1+L2 de TODAS as instancias")
    void eventDrivenInvalidation() {
        assertThat(cacheA.get("P-004").orElseThrow().price()).isEqualByComparingTo("280.00");
        assertThat(cacheB.get("P-004").orElseThrow().price()).isEqualByComparingTo("280.00");

        RestClient.create("http://localhost:" + a.getEnvironment().getProperty("local.server.port")).put()
                .uri("/catalog/products/P-004").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Headset gamer", "price", 199.90, "active", true)).retrieve().toBodilessEntity();

        // L1 tem TTL de 10 minutos: so a invalidacao por evento explica a atualizacao
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(cacheA.get("P-004").orElseThrow().price()).isEqualByComparingTo("199.90");
            assertThat(cacheB.get("P-004").orElseThrow().price()).isEqualByComparingTo("199.90");
        });
        MeterRegistry meters = b.getBean(MeterRegistry.class);
        assertThat(meters.get("catalog.cache.invalidations").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Redis indisponivel: leitura degrada para o banco (sem erro para o chamador)")
    void redisDownFallsBackToDatabase() {
        // porta sem ninguem escutando
        int deadPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            deadPort = s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        ConfigurableApplicationContext c = start("orders_cache_it", deadPort);
        try {
            ProductCache cache = c.getBean(ProductCache.class);
            assertThat(cache.get("P-005")).isPresent();
            assertThat(cache.stats().dbLoads()).isEqualTo(1);
            assertThat(cache.stats().redisErrors()).isGreaterThan(0);
            assertThat(cache.get("P-005")).isPresent(); // segue servindo do L1
        } finally {
            c.close();
        }
    }

    private static void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                task.run();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}

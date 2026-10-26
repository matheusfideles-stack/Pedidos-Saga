package com.saga.order.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.saga.common.events.EventJson;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Catalogo de produtos em cache, padrao cache-aside em duas camadas:
 * <pre>
 *   leitura -> L1 Caffeine (por instancia) -> L2 Redis (compartilhado) -> PostgreSQL
 * </pre>
 * <ul>
 *   <li><b>TTL com jitter</b>: evita que milhares de chaves populadas juntas expirem juntas.</li>
 *   <li><b>Anti-stampede</b>: single-flight por chave no L1 (Caffeine computa uma vez por chave) e lock
 *       distribuido no Redis ({@code SET NX PX}) na recarga; quem perde o lock espera o Redis ser populado.</li>
 *   <li><b>Invalidacao por evento</b>: {@link #invalidate} e chamado quando o ProductChanged chega via CDC.</li>
 *   <li><b>Degradacao</b>: se o Redis falhar (timeout curto), a leitura cai para o banco.</li>
 *   <li><b>Metricas</b>: hits/misses e hit ratio de cada camada.</li>
 * </ul>
 */
@Component
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    private static final String KEY_PREFIX = "catalog:product:";
    private static final String LOCK_PREFIX = "catalog:lock:";
    private static final String NEGATIVE_MARKER = "__none__";
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** Valor guardado no L1. {@code product == null} representa "produto inexistente" (cache negativo). */
    record Entry(Product product, long ttlNanos) {
    }

    /** Foto das estatisticas (tambem exposta em /catalog/cache/stats). */
    public record Stats(long l1Hits, long l1Misses, long l2Hits, long l2Misses, long dbLoads, long redisErrors) {
        public double l1HitRatio() {
            long total = l1Hits + l1Misses;
            return total == 0 ? 0 : (double) l1Hits / total;
        }

        public double l2HitRatio() {
            long total = l2Hits + l2Misses;
            return total == 0 ? 0 : (double) l2Hits / total;
        }
    }

    private final CatalogRepository repository;
    private final StringRedisTemplate redis;
    private final CacheProperties props;
    private final MeterRegistry meters;
    private final Cache<String, Entry> l1;
    /** Maior versao de produto ja invalidada: evita recachear uma leitura antiga do banco. */
    private final ConcurrentHashMap<String, Long> minVersion = new ConcurrentHashMap<>();

    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l1Misses = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l2Misses = new AtomicLong();
    private final AtomicLong dbLoads = new AtomicLong();
    private final AtomicLong redisErrors = new AtomicLong();

    public ProductCache(CatalogRepository repository, StringRedisTemplate redis, CacheProperties props,
                        MeterRegistry meters) {
        this.repository = repository;
        this.redis = redis;
        this.props = props;
        this.meters = meters;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(props.getL1MaxSize())
                .expireAfter(new Expiry<String, Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
        for (String layer : List.of("l1", "l2")) {
            Gauge.builder("catalog.cache.hit.ratio", this, c -> layer.equals("l1") ? c.stats().l1HitRatio()
                            : c.stats().l2HitRatio())
                    .description("Hit ratio por camada do cache do catalogo").tag("layer", layer).register(meters);
        }
    }

    public Optional<Product> get(String productId) {
        Entry cached = l1.getIfPresent(productId);
        if (cached != null) {
            count(l1Hits, "l1", "hit");
            return Optional.ofNullable(cached.product());
        }
        count(l1Misses, "l1", "miss");
        // single-flight por chave: threads concorrentes na mesma chave esperam UMA carga
        Entry loaded = l1.get(productId, this::loadFromL2OrDatabase);
        return Optional.ofNullable(loaded.product());
    }

    /** Invalida a chave em todas as camadas desta instancia (L1) e no Redis (compartilhado). */
    public void invalidate(String productId, long version) {
        minVersion.merge(productId, version, Math::max);
        l1.invalidate(productId);
        try {
            redis.delete(KEY_PREFIX + productId);
        } catch (RuntimeException e) {
            redisErrors.incrementAndGet();
            log.warn("falha ao invalidar {} no Redis: {}", productId, e.toString());
        }
        meters.counter("catalog.cache.invalidations").increment();
    }

    public Stats stats() {
        return new Stats(l1Hits.get(), l1Misses.get(), l2Hits.get(), l2Misses.get(), dbLoads.get(), redisErrors.get());
    }

    // ------------------------------------------------------------------ carga

    private Entry loadFromL2OrDatabase(String productId) {
        Entry fromRedis = readRedis(productId, true);
        if (fromRedis != null) {
            return fromRedis;
        }
        String lockKey = LOCK_PREFIX + productId;
        String token = UUID.randomUUID().toString();
        boolean locked = tryLock(lockKey, token);
        if (!locked) {
            // outra instancia esta recarregando: espera o Redis ser populado (sem ir ao banco)
            long deadline = System.nanoTime() + props.getLockWait().toNanos();
            while (System.nanoTime() < deadline) {
                sleep(15);
                Entry e = readRedis(productId, false);
                if (e != null) {
                    return e;
                }
            }
            // fail-open: o vencedor demorou ou falhou; carrega do banco para nao travar o pedido
        }
        try {
            Entry fromDb = loadFromDatabase(productId);
            writeRedis(productId, fromDb);
            return fromDb;
        } finally {
            if (locked) {
                unlock(lockKey, token);
            }
        }
    }

    private Entry loadFromDatabase(String productId) {
        dbLoads.incrementAndGet();
        meters.counter("catalog.cache.requests", "layer", "db", "result", "load").increment();
        Product p = repository.findById(productId).orElse(null);
        long l1Ttl = jitter(p == null ? props.getNegativeTtl() : props.getL1Ttl());
        if (p != null && p.version() < minVersion.getOrDefault(productId, 0L)) {
            l1Ttl = TimeUnit.MILLISECONDS.toNanos(1); // leitura mais antiga que uma invalidacao: nao reter
        }
        return new Entry(p, l1Ttl);
    }

    // ------------------------------------------------------------------ Redis (L2)

    private Entry readRedis(String productId, boolean countStats) {
        try {
            String raw = redis.opsForValue().get(KEY_PREFIX + productId);
            if (raw == null) {
                if (countStats) {
                    count(l2Misses, "l2", "miss");
                }
                return null;
            }
            if (countStats) {
                count(l2Hits, "l2", "hit");
            }
            if (NEGATIVE_MARKER.equals(raw)) {
                return new Entry(null, jitter(props.getNegativeTtl()));
            }
            return new Entry(EventJson.MAPPER.readValue(raw, Product.class), jitter(props.getL1Ttl()));
        } catch (Exception e) {
            redisErrors.incrementAndGet();
            meters.counter("catalog.cache.errors", "layer", "l2").increment();
            if (countStats) {
                count(l2Misses, "l2", "miss");
            }
            log.warn("Redis indisponivel na leitura de {}: {}", productId, e.toString());
            return null;
        }
    }

    private void writeRedis(String productId, Entry entry) {
        Product p = entry.product();
        if (p != null && p.version() < minVersion.getOrDefault(productId, 0L)) {
            return; // nao recachear dado mais antigo que uma invalidacao ja recebida
        }
        try {
            String value = p == null ? NEGATIVE_MARKER : EventJson.write(p);
            Duration ttl = Duration.ofNanos(jitter(p == null ? props.getNegativeTtl() : props.getL2Ttl()));
            redis.opsForValue().set(KEY_PREFIX + productId, value, ttl);
        } catch (RuntimeException e) {
            redisErrors.incrementAndGet();
            meters.counter("catalog.cache.errors", "layer", "l2").increment();
            log.warn("Redis indisponivel na escrita de {}: {}", productId, e.toString());
        }
    }

    private boolean tryLock(String key, String token) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, props.getLockTtl()));
        } catch (RuntimeException e) {
            redisErrors.incrementAndGet();
            return true; // sem Redis nao ha com quem competir: segue para o banco
        }
    }

    private void unlock(String key, String token) {
        try {
            redis.execute(UNLOCK, List.of(key), token);
        } catch (RuntimeException e) {
            redisErrors.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ utilidades

    /** ttl * (1 + aleatorio em [-j, +j]) em nanos. */
    long jitter(Duration base) {
        double j = props.getTtlJitter();
        double factor = 1.0 + (j == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-j, j));
        return (long) (base.toNanos() * factor);
    }

    private void count(AtomicLong counter, String layer, String result) {
        counter.incrementAndGet();
        meters.counter("catalog.cache.requests", "layer", layer, "result", result).increment();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

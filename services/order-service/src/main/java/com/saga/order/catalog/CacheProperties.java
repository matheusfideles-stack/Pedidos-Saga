package com.saga.order.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.cache")
public class CacheProperties {

    /** Tamanho maximo do L1 (Caffeine). */
    private long l1MaxSize = 10_000;
    /** TTL base do L1 (curto: o L1 e por instancia e so e invalidado por evento). */
    private Duration l1Ttl = Duration.ofSeconds(30);
    /** TTL base do L2 (Redis). */
    private Duration l2Ttl = Duration.ofMinutes(5);
    /** Fracao de jitter aplicada aos TTLs: o TTL final fica em [ttl*(1-j), ttl*(1+j)]. */
    private double ttlJitter = 0.2;
    /** Produto inexistente tambem e cacheado (por pouco tempo) para nao martelar o banco. */
    private Duration negativeTtl = Duration.ofSeconds(15);
    /** Expiracao do lock de recarga (protecao contra cache stampede entre instancias). */
    private Duration lockTtl = Duration.ofSeconds(5);
    /** Quanto quem perdeu o lock espera o vencedor popular o Redis antes de ir ao banco. */
    private Duration lockWait = Duration.ofSeconds(1);

    public long getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(long l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }

    public Duration getL1Ttl() {
        return l1Ttl;
    }

    public void setL1Ttl(Duration l1Ttl) {
        this.l1Ttl = l1Ttl;
    }

    public Duration getL2Ttl() {
        return l2Ttl;
    }

    public void setL2Ttl(Duration l2Ttl) {
        this.l2Ttl = l2Ttl;
    }

    public double getTtlJitter() {
        return ttlJitter;
    }

    public void setTtlJitter(double ttlJitter) {
        this.ttlJitter = ttlJitter;
    }

    public Duration getNegativeTtl() {
        return negativeTtl;
    }

    public void setNegativeTtl(Duration negativeTtl) {
        this.negativeTtl = negativeTtl;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public Duration getLockWait() {
        return lockWait;
    }

    public void setLockWait(Duration lockWait) {
        this.lockWait = lockWait;
    }
}

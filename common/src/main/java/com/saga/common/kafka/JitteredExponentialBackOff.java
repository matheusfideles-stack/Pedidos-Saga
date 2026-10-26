package com.saga.common.kafka;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Backoff exponencial com jitter e numero maximo de tentativas.
 * intervalo(n) = min(maxInterval, initial * multiplier^n) + aleatorio[0, jitter].
 * O jitter evita que varias replicas facam retry no mesmo instante (thundering herd).
 */
public class JitteredExponentialBackOff implements BackOff {

    private final int maxRetries;
    private final long initialMs;
    private final double multiplier;
    private final long maxMs;
    private final long jitterMs;

    public JitteredExponentialBackOff(int maxRetries, long initialMs, double multiplier, long maxMs, long jitterMs) {
        this.maxRetries = maxRetries;
        this.initialMs = initialMs;
        this.multiplier = multiplier;
        this.maxMs = maxMs;
        this.jitterMs = jitterMs;
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int attempt;

            @Override
            public long nextBackOff() {
                if (attempt >= maxRetries) {
                    return STOP;
                }
                double base = Math.min((double) maxMs, initialMs * Math.pow(multiplier, attempt));
                attempt++;
                long jitter = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs + 1) : 0;
                return (long) base + jitter;
            }
        };
    }
}

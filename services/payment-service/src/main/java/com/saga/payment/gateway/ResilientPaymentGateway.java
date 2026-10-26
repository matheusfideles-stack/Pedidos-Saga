package com.saga.payment.gateway;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Chamadas ao gateway protegidas por Resilience4j. Ordem (de fora para dentro):
 * Retry (so erros transitorios, backoff exponencial + jitter) -> CircuitBreaker -> Bulkhead -> TimeLimiter -> HTTP.
 * Cada tentativa de retry passa de novo pelo circuit breaker, que abre se a taxa de falha ficar alta.
 */
@Component
@Primary
public class ResilientPaymentGateway implements PaymentGateway {

    private static final String INSTANCE = "gateway";

    private final HttpPaymentGateway http;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    // ContextExecutorService leva o contexto de tracing para a thread da chamada HTTP (o span nao se perde)
    private final ExecutorService callExecutor =
            io.micrometer.context.ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public ResilientPaymentGateway(HttpPaymentGateway http, RetryRegistry retries, CircuitBreakerRegistry breakers,
                                   BulkheadRegistry bulkheads, TimeLimiterRegistry timeLimiters) {
        this.http = http;
        this.retry = retries.retry(INSTANCE);
        this.circuitBreaker = breakers.circuitBreaker(INSTANCE);
        this.bulkhead = bulkheads.bulkhead(INSTANCE);
        this.timeLimiter = timeLimiters.timeLimiter(INSTANCE);
    }

    @Override
    public GatewayResult charge(String idempotencyKey, String customerId, BigDecimal amount) {
        return execute(() -> http.charge(idempotencyKey, customerId, amount));
    }

    @Override
    public Optional<GatewayResult> lookup(String idempotencyKey) {
        return execute(() -> http.lookup(idempotencyKey));
    }

    @Override
    public GatewayResult voidCharge(String idempotencyKey) {
        return execute(() -> http.voidCharge(idempotencyKey));
    }

    private <T> T execute(Supplier<T> call) {
        try {
            return Decorators.ofCompletionStage(() -> CompletableFuture.supplyAsync(call, callExecutor))
                    .withTimeLimiter(timeLimiter, scheduler)
                    .withBulkhead(bulkhead)
                    .withCircuitBreaker(circuitBreaker)
                    .withRetry(retry, scheduler)
                    .get()
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @PreDestroy
    void shutdown() {
        callExecutor.shutdownNow();
        scheduler.shutdownNow();
    }
}

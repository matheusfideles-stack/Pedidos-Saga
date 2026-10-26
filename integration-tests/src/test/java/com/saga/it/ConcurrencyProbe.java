package com.saga.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Prova de "nenhum evento processado em paralelo para o mesmo pedido": envolve TODA invocacao de listener
 * (de todas as replicas) e conta quantos processamentos do mesmo (consumer group, orderId) estao em voo.
 * Se algum dia dois estiverem ao mesmo tempo, registra uma violacao.
 */
public class ConcurrencyProbe implements RecordInterceptor<Object, Object> {

    /** Estado compartilhado entre as replicas (cada contexto Spring ganha a sua instancia do interceptor). */
    public static final class Shared {
        final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        final AtomicInteger violations = new AtomicInteger();
        final Map<String, LongAdder> perInstance = new ConcurrentHashMap<>();
        final Map<String, LongAdder> perOrderDeliveries = new ConcurrentHashMap<>();

        public int violations() {
            return violations.get();
        }

        public Map<String, LongAdder> perInstance() {
            return perInstance;
        }

        public void reset() {
            inFlight.clear();
            violations.set(0);
            perInstance.clear();
            perOrderDeliveries.clear();
        }
    }

    private final Shared shared;
    private final String instance;

    public ConcurrencyProbe(Shared shared, String instance) {
        this.shared = shared;
        this.instance = instance;
    }

    private static String key(ConsumerRecord<Object, Object> rec, Consumer<Object, Object> consumer) {
        return consumer.groupMetadata().groupId() + "|" + rec.key();
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        String k = key(record, consumer);
        int now = shared.inFlight.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
        if (now > 1) {
            shared.violations.incrementAndGet();
        }
        shared.perInstance.computeIfAbsent(instance, x -> new LongAdder()).increment();
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        shared.inFlight.get(key(record, consumer)).decrementAndGet();
    }
}

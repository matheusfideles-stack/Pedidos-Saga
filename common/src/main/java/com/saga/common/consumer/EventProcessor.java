package com.saga.common.consumer;

import com.saga.common.events.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumidor idempotente. O registro em {@code processed_events} e o efeito de negocio (incluindo
 * a escrita no outbox) acontecem na MESMA transacao local: ou tudo e aplicado, ou nada.
 * Um evento duplicado (at-least-once) e descartado sem efeito.
 */
@Component
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public EventProcessor(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** @return true se o evento foi aplicado; false se era duplicata. */
    @Transactional
    public boolean process(String consumer, EventEnvelope event, Consumer<EventEnvelope> handler) {
        String orderId = event.data().path("orderId").asText(null);
        MDC.put("eventId", event.eventId().toString());
        MDC.put("eventType", event.type());
        if (orderId != null) {
            MDC.put("orderId", orderId);
        }
        try {
            int inserted = jdbc.update("""
                    INSERT INTO processed_events (event_id, consumer) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, event.eventId(), consumer);
            if (inserted == 0) {
                meters.counter("saga.events.processed", "consumer", consumer, "type", event.type(), "outcome", "duplicate")
                        .increment();
                log.info("evento duplicado descartado");
                return false;
            }
            handler.accept(event);
            meters.counter("saga.events.processed", "consumer", consumer, "type", event.type(), "outcome", "applied")
                    .increment();
            Timer.builder("saga.event.latency")
                    .description("Tempo entre a emissao do evento e o fim do seu processamento (latencia da etapa)")
                    .tags("consumer", consumer, "type", event.type())
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(event.occurredAt(), Instant.now()));
            return true;
        } finally {
            MDC.remove("eventId");
            MDC.remove("eventType");
            MDC.remove("orderId");
        }
    }
}

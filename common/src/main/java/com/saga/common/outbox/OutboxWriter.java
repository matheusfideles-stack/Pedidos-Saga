package com.saga.common.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.saga.common.events.EventEnvelope;
import com.saga.common.events.EventJson;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox: grava o evento na tabela {@code outbox} usando a transacao corrente.
 * A publicacao no Kafka e feita pelo Debezium (CDC) - nao existe polling proprio.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<Tracer> tracer;

    public OutboxWriter(JdbcTemplate jdbc, ObjectProvider<Tracer> tracer) {
        this.jdbc = jdbc;
        this.tracer = tracer;
    }

    /** Exige transacao ativa: o evento so existe se a mudanca de negocio for commitada. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(String aggregateType, String aggregateId, String eventType, int version, Object data) {
        UUID eventId = UUID.randomUUID();
        JsonNode body = EventJson.MAPPER.valueToTree(data);
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, version, aggregateId, Instant.now(), body);
        jdbc.update("""
                INSERT INTO outbox (id, aggregatetype, aggregateid, type, payload, traceparent)
                VALUES (?, ?, ?, ?, ?, ?)
                """, eventId, aggregateType, aggregateId, eventType, EventJson.write(envelope), currentTraceparent());
        return eventId;
    }

    /** Guarda o contexto W3C no outbox; o Debezium o copia para um header Kafka (traceparent). */
    private String currentTraceparent() {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return null;
        }
        Span span = t.currentSpan();
        if (span == null) {
            return null;
        }
        return "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01";
    }
}

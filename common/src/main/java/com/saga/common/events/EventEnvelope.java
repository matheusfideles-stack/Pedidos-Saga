package com.saga.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelope versionado de todos os eventos da saga. E o valor da mensagem no Kafka
 * (o Debezium EventRouter publica a coluna {@code outbox.payload} como valor).
 *
 * @param eventId     identificador unico; chave de idempotencia dos consumidores
 * @param type        nome do evento (ex.: OrderCreated)
 * @param version     versao do contrato do evento (ver docs/event-contracts.md)
 * @param aggregateId chave de particao (orderId; productId para o catalogo)
 */
public record EventEnvelope(
        UUID eventId,
        String type,
        int version,
        String aggregateId,
        Instant occurredAt,
        JsonNode data) {
}

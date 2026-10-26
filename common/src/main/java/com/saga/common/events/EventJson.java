package com.saga.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** (De)serializacao do envelope. Tolerante a campos desconhecidos (compatibilidade aditiva). */
public final class EventJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static EventEnvelope parse(String json) {
        try {
            EventEnvelope env = MAPPER.readValue(json, EventEnvelope.class);
            if (env.eventId() == null || env.type() == null || env.data() == null) {
                throw new PoisonMessageException("envelope incompleto: " + json);
            }
            return env;
        } catch (JsonProcessingException e) {
            throw new PoisonMessageException("envelope invalido: " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T data(EventEnvelope env, Class<T> type) {
        try {
            return MAPPER.treeToValue(env.data(), type);
        } catch (JsonProcessingException e) {
            throw new PoisonMessageException("dados invalidos para " + env.type() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private EventJson() {
    }
}

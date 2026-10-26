package com.saga.order.catalog;

import com.saga.common.events.EventEnvelope;
import com.saga.common.events.EventJson;
import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Invalidacao de cache orientada a eventos: ProductChanged (vindo do outbox via Debezium) remove a chave
 * do L1 e do L2. Cada INSTANCIA precisa receber o evento (o L1 e local), por isso o consumer group e unico
 * por instancia e a leitura comeca do fim do topico. A operacao e naturalmente idempotente.
 */
@Component
public class CatalogInvalidationListener {

    private final ProductCache cache;

    public CatalogInvalidationListener(ProductCache cache) {
        this.cache = cache;
    }

    @KafkaListener(id = "catalog-cache-invalidation", topics = Topics.CATALOG_EVENTS,
            groupId = "order-service-cache-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventJson.parse(record.value());
        if (Events.PRODUCT_CHANGED.equals(event.type())) {
            Events.ProductChanged changed = EventJson.data(event, Events.ProductChanged.class);
            cache.invalidate(changed.productId(), changed.version());
        }
    }
}

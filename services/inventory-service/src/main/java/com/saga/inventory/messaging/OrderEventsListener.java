package com.saga.inventory.messaging;

import com.saga.common.consumer.EventProcessor;
import com.saga.common.events.EventEnvelope;
import com.saga.common.events.EventJson;
import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.inventory.service.InventoryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Saga coreografada: o estoque reage a OrderCreated (reserva) e OrderCancelled (compensacao). */
@Component
public class OrderEventsListener {

    public static final String CONSUMER = "inventory-service";

    private final EventProcessor processor;
    private final InventoryService inventory;

    public OrderEventsListener(EventProcessor processor, InventoryService inventory) {
        this.processor = processor;
        this.inventory = inventory;
    }

    @KafkaListener(id = "inventory-saga", groupId = CONSUMER, topics = Topics.ORDER_EVENTS)
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventJson.parse(record.value());
        processor.process(CONSUMER, event, this::handle);
    }

    private void handle(EventEnvelope event) {
        switch (event.type()) {
            case Events.ORDER_CREATED -> inventory.reserve(EventJson.data(event, Events.OrderCreated.class));
            case Events.ORDER_CANCELLED -> inventory.release(EventJson.data(event, Events.OrderCancelled.class).orderId());
            case Events.ORDER_CONFIRMED -> inventory.commit(EventJson.data(event, Events.OrderConfirmed.class).orderId());
            default -> {
                // eventos desconhecidos sao ignorados (compatibilidade para frente)
            }
        }
    }
}

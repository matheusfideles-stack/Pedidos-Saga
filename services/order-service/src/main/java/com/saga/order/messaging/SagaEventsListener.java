package com.saga.order.messaging;

import com.saga.common.consumer.EventProcessor;
import com.saga.common.events.EventEnvelope;
import com.saga.common.events.EventJson;
import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.order.domain.SagaTrigger;
import com.saga.order.service.OrderSagaService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reage aos eventos de estoque e pagamento (saga coreografada). */
@Component
public class SagaEventsListener {

    public static final String CONSUMER = "order-service";
    private static final Logger log = LoggerFactory.getLogger(SagaEventsListener.class);

    private final EventProcessor processor;
    private final OrderSagaService saga;

    public SagaEventsListener(EventProcessor processor, OrderSagaService saga) {
        this.processor = processor;
        this.saga = saga;
    }

    @KafkaListener(id = "order-saga", groupId = CONSUMER, topics = {Topics.INVENTORY_EVENTS, Topics.PAYMENT_EVENTS})
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventJson.parse(record.value());
        processor.process(CONSUMER, event, this::handle);
    }

    private void handle(EventEnvelope event) {
        switch (event.type()) {
            case Events.INVENTORY_RESERVED -> {
                Events.InventoryReserved d = EventJson.data(event, Events.InventoryReserved.class);
                saga.apply(d.orderId(), SagaTrigger.INVENTORY_RESERVED, null);
            }
            case Events.INVENTORY_REJECTED -> {
                Events.InventoryRejected d = EventJson.data(event, Events.InventoryRejected.class);
                saga.apply(d.orderId(), SagaTrigger.INVENTORY_REJECTED, "inventory: " + d.reason());
            }
            case Events.PAYMENT_AUTHORIZED -> {
                Events.PaymentAuthorized d = EventJson.data(event, Events.PaymentAuthorized.class);
                saga.apply(d.orderId(), SagaTrigger.PAYMENT_AUTHORIZED, null);
            }
            case Events.PAYMENT_DECLINED -> {
                Events.PaymentDeclined d = EventJson.data(event, Events.PaymentDeclined.class);
                saga.apply(d.orderId(), SagaTrigger.PAYMENT_DECLINED, "payment: " + d.reason());
            }
            default -> log.debug("evento {} nao interessa ao order-service", event.type());
        }
    }
}

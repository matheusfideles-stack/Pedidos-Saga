package com.saga.payment.messaging;

import com.saga.common.consumer.EventProcessor;
import com.saga.common.events.EventEnvelope;
import com.saga.common.events.EventJson;
import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.payment.domain.Payment;
import com.saga.payment.gateway.PaymentGateway;
import com.saga.payment.service.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Saga coreografada: cobra quando o estoque foi reservado; estorna quando o pedido e cancelado. */
@Component
public class SagaEventsListener {

    public static final String CONSUMER = "payment-service";

    private final EventProcessor processor;
    private final PaymentService payments;
    private final PaymentGateway gateway;
    private final MeterRegistry meters;

    public SagaEventsListener(EventProcessor processor, PaymentService payments, PaymentGateway gateway,
                              MeterRegistry meters) {
        this.processor = processor;
        this.payments = payments;
        this.gateway = gateway;
        this.meters = meters;
    }

    @KafkaListener(id = "payment-saga", groupId = CONSUMER, topics = {Topics.INVENTORY_EVENTS, Topics.ORDER_EVENTS})
    public void onEvent(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventJson.parse(record.value());
        switch (event.type()) {
            case Events.INVENTORY_RESERVED -> charge(event);
            case Events.ORDER_CANCELLED -> processor.process(CONSUMER, event,
                    e -> payments.cancelForOrder(EventJson.data(e, Events.OrderCancelled.class).orderId()));
            default -> {
                // eventos que nao interessam ao pagamento
            }
        }
    }

    private void charge(EventEnvelope event) {
        Events.InventoryReserved reserved = EventJson.data(event, Events.InventoryReserved.class);

        Payment payment = payments.begin(reserved);                     // tx1
        if (payment.status() != Payment.Status.PENDING) {
            // ja decidido (duplicata/reentrega) ou cancelado: nao cobra de novo
            processor.process(CONSUMER, event, e -> { });
            return;
        }
        PaymentGateway.GatewayResult result = Timer.builder("payment.gateway.charge")   // chamada de rede, sem transacao
                .publishPercentileHistogram().register(meters)
                .record(() -> gateway.charge(payment.id().toString(), payment.customerId(), payment.amount()));
        processor.process(CONSUMER, event, e -> payments.finalizeCharge(payment.id(), result)); // tx2
    }
}

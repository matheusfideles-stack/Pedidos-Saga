package com.saga.order.service;

import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.common.outbox.OutboxWriter;
import com.saga.order.domain.Order;
import com.saga.order.domain.OrderStateMachine;
import com.saga.order.domain.OrderStatus;
import com.saga.order.domain.SagaTrigger;
import com.saga.order.persistence.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica transicoes da saga ao pedido. Cada transicao, a linha do pedido e o evento de saida
 * (outbox) fazem parte da mesma transacao ACID local.
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public OrderSagaService(OrderRepository orders, OutboxWriter outbox, MeterRegistry meters) {
        this.orders = orders;
        this.outbox = outbox;
        this.meters = meters;
    }

    /** @return novo estado, ou vazio se o evento foi ignorado (pedido desconhecido, duplicado ou tardio). */
    @Transactional
    public Optional<OrderStatus> apply(UUID orderId, SagaTrigger trigger, String reason) {
        Optional<Order> found = orders.findForUpdate(orderId);
        if (found.isEmpty()) {
            log.warn("evento para pedido desconhecido ignorado trigger={}", trigger);
            return Optional.empty();
        }
        Order order = found.get();
        Optional<OrderStatus> next = OrderStateMachine.next(order.status(), trigger);
        if (next.isEmpty()) {
            log.info("transicao ignorada: {} em {}", trigger, order.status());
            return Optional.empty();
        }
        move(order, order.status(), next.get(), trigger, reason);
        if (next.get() == OrderStatus.PAYMENT_AUTHORIZED) {
            // pagamento autorizado e o ultimo passo: confirma na mesma transacao
            move(order, OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.CONFIRMED, SagaTrigger.CONFIRM, null);
            return Optional.of(OrderStatus.CONFIRMED);
        }
        return next;
    }

    private void move(Order order, OrderStatus from, OrderStatus to, SagaTrigger trigger, String reason) {
        orders.updateStatus(order.id(), from, to, trigger.name(), reason);
        meters.counter("orders.state.transitions", "from", from.name(), "to", to.name()).increment();
        log.info("pedido {} -> {} (trigger={})", from, to, trigger);

        if (to == OrderStatus.CANCELLED) {
            outbox.write(Topics.AGG_ORDER, order.id().toString(), Events.ORDER_CANCELLED, Events.V1,
                    new Events.OrderCancelled(order.id(), reason == null ? "unspecified" : reason));
        } else if (to == OrderStatus.CONFIRMED) {
            outbox.write(Topics.AGG_ORDER, order.id().toString(), Events.ORDER_CONFIRMED, Events.V1,
                    new Events.OrderConfirmed(order.id()));
        }
        if (to.isTerminal()) {
            Timer.builder("orders.saga.duration")
                    .description("Da criacao do pedido ate o estado terminal")
                    .tag("outcome", to.name())
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(order.createdAt(), OffsetDateTime.now()));
        }
    }
}

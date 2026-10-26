package com.saga.inventory.service;

import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.common.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estoque do ponto de vista da saga. Todos os metodos rodam dentro da transacao do consumidor
 * (EventProcessor), junto com processed_events e o outbox.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public InventoryService(JdbcClient jdbc, OutboxWriter outbox, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.meters = meters;
    }

    /** Reserva TODOS os itens ou nenhum. Responde InventoryReserved ou InventoryRejected via outbox. */
    @Transactional
    public void reserve(Events.OrderCreated order) {
        String orderId = order.orderId().toString();

        // Defesa extra: o mesmo pedido nunca reserva duas vezes (alem do processed_events por eventId).
        Integer existing = jdbc.sql("SELECT count(*) FROM reservations WHERE order_id = :o")
                .param("o", order.orderId()).query(Integer.class).single();
        if (existing > 0) {
            log.info("pedido ja possui reserva; ignorando");
            return;
        }

        // Ordena por produto e trava as linhas: evita deadlock entre pedidos concorrentes.
        List<Events.Item> items = new ArrayList<>(order.items());
        items.sort(Comparator.comparing(Events.Item::productId));

        String shortage = null;
        for (Events.Item item : items) {
            Integer available = jdbc.sql("SELECT available FROM stock WHERE product_id = :p FOR UPDATE")
                    .param("p", item.productId()).query(Integer.class).optional().orElse(null);
            if (available == null) {
                shortage = "produto desconhecido " + item.productId();
                break;
            }
            if (available < item.quantity()) {
                shortage = "estoque insuficiente para " + item.productId()
                        + " (disponivel=" + available + ", pedido=" + item.quantity() + ")";
                break;
            }
        }

        if (shortage != null) {
            outbox.write(Topics.AGG_INVENTORY, orderId, Events.INVENTORY_REJECTED, Events.V1,
                    new Events.InventoryRejected(order.orderId(), shortage));
            meters.counter("inventory.reservations", "result", "rejected").increment();
            log.info("reserva rejeitada: {}", shortage);
            return;
        }

        for (Events.Item item : items) {
            jdbc.sql("UPDATE stock SET available = available - :q, reserved = reserved + :q, updated_at = now() "
                            + "WHERE product_id = :p")
                    .param("q", item.quantity()).param("p", item.productId()).update();
            jdbc.sql("INSERT INTO reservations (order_id, product_id, quantity, status) VALUES (:o, :p, :q, 'RESERVED')")
                    .param("o", order.orderId()).param("p", item.productId()).param("q", item.quantity()).update();
        }
        outbox.write(Topics.AGG_INVENTORY, orderId, Events.INVENTORY_RESERVED, Events.V1,
                new Events.InventoryReserved(order.orderId(), order.customerId(), order.total(), order.currency()));
        meters.counter("inventory.reservations", "result", "reserved").increment();
    }

    /** Compensacao idempotente: libera somente reservas ainda RESERVED. */
    @Transactional
    public void release(UUID orderId) {
        List<Row> rows = jdbc.sql("""
                        SELECT product_id, quantity FROM reservations
                        WHERE order_id = :o AND status = 'RESERVED'
                        ORDER BY product_id FOR UPDATE
                        """)
                .param("o", orderId)
                .query((rs, n) -> new Row(rs.getString("product_id"), rs.getInt("quantity"))).list();
        if (rows.isEmpty()) {
            log.info("nada a liberar (sem reserva ativa)");
            return;
        }
        for (Row r : rows) {
            jdbc.sql("UPDATE stock SET available = available + :q, reserved = reserved - :q, updated_at = now() "
                            + "WHERE product_id = :p")
                    .param("q", r.quantity()).param("p", r.productId()).update();
        }
        jdbc.sql("UPDATE reservations SET status = 'RELEASED', updated_at = now() WHERE order_id = :o AND status = 'RESERVED'")
                .param("o", orderId).update();
        outbox.write(Topics.AGG_INVENTORY, orderId.toString(), Events.INVENTORY_RELEASED, Events.V1,
                new Events.InventoryReleased(orderId));
        meters.counter("inventory.reservations", "result", "released").increment();
    }

    /** Pedido confirmado: o item sai do estoque de vez (reserved -> consumido). */
    @Transactional
    public void commit(UUID orderId) {
        List<Row> rows = jdbc.sql("""
                        SELECT product_id, quantity FROM reservations
                        WHERE order_id = :o AND status = 'RESERVED'
                        ORDER BY product_id FOR UPDATE
                        """)
                .param("o", orderId)
                .query((rs, n) -> new Row(rs.getString("product_id"), rs.getInt("quantity"))).list();
        for (Row r : rows) {
            jdbc.sql("UPDATE stock SET reserved = reserved - :q, updated_at = now() WHERE product_id = :p")
                    .param("q", r.quantity()).param("p", r.productId()).update();
        }
        jdbc.sql("UPDATE reservations SET status = 'COMMITTED', updated_at = now() WHERE order_id = :o AND status = 'RESERVED'")
                .param("o", orderId).update();
    }

    private record Row(String productId, int quantity) {
    }
}

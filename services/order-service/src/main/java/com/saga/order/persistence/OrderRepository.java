package com.saga.order.persistence;

import com.saga.common.events.Events;
import com.saga.order.domain.Order;
import com.saga.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String customerId, BigDecimal total, String currency, List<Events.Item> items) {
        jdbc.sql("""
                        INSERT INTO orders (id, customer_id, status, total, currency)
                        VALUES (:id, :customer, 'PENDING', :total, :currency)
                        """)
                .param("id", id).param("customer", customerId).param("total", total).param("currency", currency)
                .update();
        for (Events.Item item : items) {
            jdbc.sql("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (:o, :p, :q, :u)")
                    .param("o", id).param("p", item.productId()).param("q", item.quantity())
                    .param("u", item.unitPrice()).update();
        }
        recordHistory(id, null, OrderStatus.PENDING, "ORDER_CREATED");
    }

    public Optional<Order> find(UUID id) {
        return load(id, false);
    }

    /** Lock pessimista da linha: serializa transicoes concorrentes (consumidores e HTTP) do mesmo pedido. */
    public Optional<Order> findForUpdate(UUID id) {
        return load(id, true);
    }

    private Optional<Order> load(UUID id, boolean lock) {
        return jdbc.sql("SELECT id, customer_id, status, total, currency, cancel_reason, created_at, updated_at "
                        + "FROM orders WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, n) -> new Order(
                        rs.getObject("id", UUID.class),
                        rs.getString("customer_id"),
                        OrderStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("total"),
                        rs.getString("currency"),
                        rs.getString("cancel_reason"),
                        List.of(),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional()
                .map(o -> new Order(o.id(), o.customerId(), o.status(), o.total(), o.currency(), o.cancelReason(),
                        items(id), o.createdAt(), o.updatedAt()));
    }

    private List<Events.Item> items(UUID orderId) {
        return jdbc.sql("SELECT product_id, quantity, unit_price FROM order_items WHERE order_id = :id ORDER BY product_id")
                .param("id", orderId)
                .query((rs, n) -> new Events.Item(rs.getString("product_id"), rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price")))
                .list();
    }

    public void updateStatus(UUID id, OrderStatus from, OrderStatus to, String trigger, String cancelReason) {
        jdbc.sql("UPDATE orders SET status = :to, cancel_reason = COALESCE(:reason, cancel_reason), updated_at = now() "
                        + "WHERE id = :id")
                .param("to", to.name()).param("reason", cancelReason).param("id", id).update();
        recordHistory(id, from, to, trigger);
    }

    private void recordHistory(UUID id, OrderStatus from, OrderStatus to, String trigger) {
        jdbc.sql("INSERT INTO order_status_history (order_id, from_status, to_status, trigger) VALUES (:o, :f, :t, :tr)")
                .param("o", id).param("f", from == null ? null : from.name()).param("t", to.name()).param("tr", trigger)
                .update();
    }

    public List<String> history(UUID id) {
        return jdbc.sql("SELECT to_status FROM order_status_history WHERE order_id = :id ORDER BY id")
                .param("id", id).query(String.class).list();
    }
}

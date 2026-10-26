package com.saga.payment.persistence;

import com.saga.payment.domain.Payment;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String COLS = "id, order_id, customer_id, amount, currency, status, gateway_ref, "
            + "failure_reason, gateway_voided, created_at, updated_at";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cria o pagamento PENDING se ainda nao existe para o pedido (UNIQUE order_id => cobranca unica). */
    public void insertPendingIfAbsent(UUID id, UUID orderId, String customerId, BigDecimal amount, String currency) {
        jdbc.sql("""
                        INSERT INTO payments (id, order_id, customer_id, amount, currency, status)
                        VALUES (:id, :o, :c, :a, :cur, 'PENDING') ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", id).param("o", orderId).param("c", customerId).param("a", amount).param("cur", currency)
                .update();
    }

    /** Tombstone: cancelamento chegou antes de qualquer cobranca; impede cobrar depois. */
    public void insertCancelledIfAbsent(UUID orderId) {
        jdbc.sql("""
                        INSERT INTO payments (id, order_id, customer_id, amount, currency, status, gateway_voided)
                        VALUES (:id, :o, 'n/a', 0, 'BRL', 'CANCELLED', TRUE) ON CONFLICT (order_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("o", orderId).update();
    }

    public Optional<Payment> findByOrder(UUID orderId) {
        return query("SELECT " + COLS + " FROM payments WHERE order_id = :o", "o", orderId);
    }

    public Optional<Payment> findByIdForUpdate(UUID id) {
        return query("SELECT " + COLS + " FROM payments WHERE id = :o FOR UPDATE", "o", id);
    }

    public Optional<Payment> findByOrderForUpdate(UUID orderId) {
        return query("SELECT " + COLS + " FROM payments WHERE order_id = :o FOR UPDATE", "o", orderId);
    }

    private Optional<Payment> query(String sql, String name, Object value) {
        return jdbc.sql(sql).param(name, value).query(this::map).optional();
    }

    public void updateStatus(UUID id, Payment.Status status, String gatewayRef, String failureReason) {
        jdbc.sql("UPDATE payments SET status = :s, gateway_ref = COALESCE(:r, gateway_ref), "
                        + "failure_reason = :f, updated_at = now() WHERE id = :id")
                .param("s", status.name()).param("r", gatewayRef).param("f", failureReason).param("id", id).update();
    }

    public void markCancelled(UUID id) {
        jdbc.sql("UPDATE payments SET status = 'CANCELLED', gateway_voided = FALSE, updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    public void markVoided(UUID id) {
        jdbc.sql("UPDATE payments SET gateway_voided = TRUE, updated_at = now() WHERE id = :id").param("id", id).update();
    }

    /** PENDING antigos: candidatos da reconciliacao. */
    public List<Payment> findStalePending(Duration olderThan, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM payments WHERE status = 'PENDING' "
                        + "AND updated_at < now() - make_interval(secs => :secs) ORDER BY updated_at LIMIT :lim")
                .param("secs", (double) olderThan.toSeconds()).param("lim", limit).query(this::map).list();
    }

    /** Pagamentos cancelados cujo estorno no gateway ainda nao foi confirmado. */
    public List<Payment> findUnvoidedCancelled(Duration olderThan, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM payments WHERE status = 'CANCELLED' AND NOT gateway_voided "
                        + "AND updated_at < now() - make_interval(secs => :secs) ORDER BY updated_at LIMIT :lim")
                .param("secs", (double) olderThan.toSeconds()).param("lim", limit).query(this::map).list();
    }

    private Payment map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getString("customer_id"), rs.getBigDecimal("amount"), rs.getString("currency").trim(),
                Payment.Status.valueOf(rs.getString("status")), rs.getString("gateway_ref"),
                rs.getString("failure_reason"), rs.getBoolean("gateway_voided"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}

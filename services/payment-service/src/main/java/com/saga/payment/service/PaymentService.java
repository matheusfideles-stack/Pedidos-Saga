package com.saga.payment.service;

import com.saga.common.events.Events;
import com.saga.common.events.Topics;
import com.saga.common.outbox.OutboxWriter;
import com.saga.payment.domain.Payment;
import com.saga.payment.gateway.PaymentGateway;
import com.saga.payment.gateway.PaymentGateway.GatewayResult;
import com.saga.payment.persistence.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regras de pagamento. A cobranca no gateway (rede) NUNCA acontece dentro de uma transacao de banco:
 * <ol>
 *   <li>tx1 {@link #begin}: grava o pagamento PENDING (UNIQUE por pedido);</li>
 *   <li>chamada ao gateway com chave de idempotencia = id do pagamento (repetivel sem cobrar duas vezes);</li>
 *   <li>tx2 {@link #finalizeCharge}: PENDING -> AUTHORIZED/DECLINED + outbox + processed_events, atomicamente.</li>
 * </ol>
 * Se o processo cair entre 1 e 3, o evento Kafka e reentregue (offset nao confirmado) ou a reconciliacao resolve.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final OutboxWriter outbox;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public PaymentService(PaymentRepository payments, OutboxWriter outbox, PaymentGateway gateway,
                          PlatformTransactionManager txManager, MeterRegistry meters) {
        this.payments = payments;
        this.outbox = outbox;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(txManager);
        this.meters = meters;
    }

    /** tx1: garante a existencia do pagamento do pedido e o devolve. */
    public Payment begin(Events.InventoryReserved reserved) {
        return tx.execute(status -> {
            payments.insertPendingIfAbsent(UUID.randomUUID(), reserved.orderId(), reserved.customerId(),
                    reserved.amount(), reserved.currency());
            return payments.findByOrder(reserved.orderId()).orElseThrow();
        });
    }

    /** tx2: aplica o resultado do gateway. Idempotente: so age se o pagamento ainda esta PENDING. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalizeCharge(UUID paymentId, GatewayResult result) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow();
        switch (p.status()) {
            case PENDING -> {
                if (result.authorized()) {
                    payments.updateStatus(p.id(), Payment.Status.AUTHORIZED, result.ref(), null);
                    outbox.write(Topics.AGG_PAYMENT, p.orderId().toString(), Events.PAYMENT_AUTHORIZED, Events.V1,
                            new Events.PaymentAuthorized(p.orderId(), p.id(), p.amount()));
                    meters.counter("payments.result", "status", "AUTHORIZED").increment();
                } else {
                    String reason = switch (result.status()) {
                        case "DECLINED" -> "declined_by_gateway";
                        case "VOIDED" -> "voided_at_gateway";
                        default -> "reconciliation_timeout";
                    };
                    payments.updateStatus(p.id(), Payment.Status.DECLINED, result.ref(), reason);
                    outbox.write(Topics.AGG_PAYMENT, p.orderId().toString(), Events.PAYMENT_DECLINED, Events.V1,
                            new Events.PaymentDeclined(p.orderId(), p.id(), reason));
                    meters.counter("payments.result", "status", "DECLINED").increment();
                }
            }
            case CANCELLED -> {
                if (result.authorized()) {
                    // pedido foi cancelado enquanto a cobranca estava em voo: estorna a autorizacao
                    log.warn("cobranca autorizada apos cancelamento; estornando");
                    afterCommit(() -> voidQuietly(p));
                }
            }
            default -> log.info("pagamento ja finalizado ({}); resultado do gateway ignorado", p.status());
        }
    }

    /** Compensacao (OrderCancelled), idempotente. Roda dentro da transacao do consumidor. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForOrder(UUID orderId) {
        Optional<Payment> found = payments.findByOrderForUpdate(orderId);
        if (found.isEmpty()) {
            payments.insertCancelledIfAbsent(orderId); // tombstone: um InventoryReserved tardio nao cobra mais
            return;
        }
        Payment p = found.get();
        switch (p.status()) {
            case PENDING -> payments.markCancelled(p.id()); // estorno ocorre na finalizacao ou na reconciliacao
            case AUTHORIZED -> {
                payments.markCancelled(p.id());
                outbox.write(Topics.AGG_PAYMENT, orderId.toString(), Events.PAYMENT_CANCELLED, Events.V1,
                        new Events.PaymentCancelled(orderId, p.id()));
                meters.counter("payments.result", "status", "CANCELLED").increment();
                afterCommit(() -> voidQuietly(p));
            }
            default -> log.info("cancelamento sem efeito: pagamento {}", p.status());
        }
    }

    /** Estorno no gateway; se falhar, a reconciliacao repete (gateway_voided continua FALSE). */
    public void voidQuietly(Payment p) {
        try {
            gateway.voidCharge(p.id().toString());
            tx.executeWithoutResult(s -> payments.markVoided(p.id()));
        } catch (Exception e) {
            log.warn("estorno de {} falhou, a reconciliacao tentara de novo: {}", p.id(), e.toString());
        }
    }

    /** Executa a acao depois do commit (fora da transacao), sem propagar excecao. */
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}

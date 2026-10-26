package com.saga.payment.service;

import com.saga.payment.PaymentProperties;
import com.saga.payment.domain.Payment;
import com.saga.payment.gateway.PaymentGateway;
import com.saga.payment.gateway.PaymentGateway.GatewayResult;
import com.saga.payment.persistence.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job de reconciliacao: compara pagamentos PENDING antigos com o gateway e resolve o estado.
 * Seguro com varias replicas: cada resolucao e protegida por "UPDATE so se ainda PENDING" (lock de linha),
 * e o gateway e idempotente. Tambem conclui estornos pendentes de pagamentos cancelados.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private static final int BATCH = 50;

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentService service;
    private final PaymentProperties props;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public PaymentReconciler(PaymentRepository payments, PaymentGateway gateway, PaymentService service,
                             PaymentProperties props, PlatformTransactionManager txManager, MeterRegistry meters) {
        this.payments = payments;
        this.gateway = gateway;
        this.service = service;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval:PT30S}", initialDelayString = "PT5S")
    public void scheduled() {
        if (props.getReconciliation().isEnabled()) {
            reconcile();
        }
    }

    /** Uma passada; exposta para testes. @return pagamentos PENDING resolvidos. */
    public int reconcile() {
        Duration stale = props.getReconciliation().getStaleAfter();
        int resolved = 0;
        List<Payment> pending = payments.findStalePending(stale, BATCH);
        for (Payment p : pending) {
            try {
                if (reconcilePending(p)) {
                    resolved++;
                }
            } catch (Exception e) {
                log.warn("reconciliacao do pagamento {} adiada: {}", p.id(), e.toString());
            }
        }
        for (Payment p : payments.findUnvoidedCancelled(stale, BATCH)) {
            service.voidQuietly(p);
        }
        return resolved;
    }

    private boolean reconcilePending(Payment p) {
        Optional<GatewayResult> remote = gateway.lookup(p.id().toString());
        GatewayResult outcome;
        if (remote.isPresent()) {
            outcome = remote.get();
        } else if (Duration.between(p.createdAt(), OffsetDateTime.now()).compareTo(props.getReconciliation().getGiveUpAfter()) > 0) {
            outcome = new GatewayResult("TIMEOUT", null); // gateway nunca viu a cobranca: desiste e compensa a saga
        } else {
            return false; // a reentrega do evento ainda pode cobrar
        }
        tx.executeWithoutResult(s -> service.finalizeCharge(p.id(), outcome));
        meters.counter("payments.reconciled", "gateway_status", outcome.status()).increment();
        log.info("pagamento {} reconciliado com o gateway: {}", p.id(), outcome.status());
        return true;
    }
}

package com.saga.payment.gateway;

import java.math.BigDecimal;
import java.util.Optional;

/** Porta para o gateway de pagamento (chamada sincrona de rede). */
public interface PaymentGateway {

    /** Cobranca idempotente: a mesma {@code idempotencyKey} nunca cobra duas vezes. */
    GatewayResult charge(String idempotencyKey, String customerId, BigDecimal amount);

    /** Consulta o estado real no gateway (usada na reconciliacao). Vazio = gateway nao conhece a chave. */
    Optional<GatewayResult> lookup(String idempotencyKey);

    /** Cancela/estorna a autorizacao. Idempotente. */
    GatewayResult voidCharge(String idempotencyKey);

    record GatewayResult(String status, String ref) {
        public boolean authorized() {
            return "AUTHORIZED".equals(status);
        }
    }
}

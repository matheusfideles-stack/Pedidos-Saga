package com.saga.order.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Visao resumida de um pedido, usada na listagem (sem itens). */
public record OrderSummary(
        UUID id,
        String customerId,
        OrderStatus status,
        BigDecimal total,
        String currency,
        OffsetDateTime createdAt) {
}

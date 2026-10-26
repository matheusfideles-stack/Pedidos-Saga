package com.saga.payment.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Payment(
        UUID id,
        UUID orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        Status status,
        String gatewayRef,
        String failureReason,
        boolean gatewayVoided,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public enum Status { PENDING, AUTHORIZED, DECLINED, CANCELLED }
}

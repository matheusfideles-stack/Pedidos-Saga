package com.saga.order.domain;

import com.saga.common.events.Events;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Order(
        UUID id,
        String customerId,
        OrderStatus status,
        BigDecimal total,
        String currency,
        String cancelReason,
        List<Events.Item> items,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}

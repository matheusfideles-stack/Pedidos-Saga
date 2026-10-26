package com.saga.order.web;

import com.saga.order.domain.Order;
import com.saga.order.domain.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    public record ItemRequest(@NotBlank @Size(max = 40) String productId, @Min(1) @Max(1000) int quantity) {
    }

    public record CreateOrderRequest(@NotBlank @Size(max = 64) String customerId,
                                     @NotEmpty @Valid List<ItemRequest> items) {
    }

    public record ItemResponse(String productId, int quantity, BigDecimal unitPrice) {
    }

    public record OrderResponse(UUID id, String customerId, OrderStatus status, BigDecimal total, String currency,
                                String cancelReason, List<ItemResponse> items, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {

        public static OrderResponse from(Order o) {
            return new OrderResponse(o.id(), o.customerId(), o.status(), o.total(), o.currency(), o.cancelReason(),
                    o.items().stream().map(i -> new ItemResponse(i.productId(), i.quantity(), i.unitPrice())).toList(),
                    o.createdAt(), o.updatedAt());
        }
    }

    public record ProductRequest(@NotBlank String name, @Min(0) BigDecimal price, Boolean active) {
    }

    public record ProductResponse(String id, String name, BigDecimal price, boolean active, long version) {
    }

    private OrderDtos() {
    }
}

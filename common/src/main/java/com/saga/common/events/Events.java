package com.saga.common.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Corpo ({@code data}) de cada tipo de evento. Politica de compatibilidade: somente mudancas
 * ADITIVAS dentro de uma mesma {@code version}; consumidores ignoram campos desconhecidos.
 */
public final class Events {

    public static final int V1 = 1;

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_REJECTED = "InventoryRejected";
    public static final String INVENTORY_RELEASED = "InventoryReleased";
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    public static final String PAYMENT_DECLINED = "PaymentDeclined";
    public static final String PAYMENT_CANCELLED = "PaymentCancelled";
    public static final String PRODUCT_CHANGED = "ProductChanged";

    public record Item(String productId, int quantity, BigDecimal unitPrice) {
    }

    public record OrderCreated(UUID orderId, String customerId, List<Item> items, BigDecimal total, String currency) {
    }

    public record OrderConfirmed(UUID orderId) {
    }

    public record OrderCancelled(UUID orderId, String reason) {
    }

    public record InventoryReserved(UUID orderId, String customerId, BigDecimal amount, String currency) {
    }

    public record InventoryRejected(UUID orderId, String reason) {
    }

    public record InventoryReleased(UUID orderId) {
    }

    public record PaymentAuthorized(UUID orderId, UUID paymentId, BigDecimal amount) {
    }

    public record PaymentDeclined(UUID orderId, UUID paymentId, String reason) {
    }

    public record PaymentCancelled(UUID orderId, UUID paymentId) {
    }

    public record ProductChanged(String productId, String name, BigDecimal price, boolean active, long version) {
    }

    private Events() {
    }
}

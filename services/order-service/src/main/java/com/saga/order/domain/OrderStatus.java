package com.saga.order.domain;

/** Estados explicitos do pedido (consistencia BASE entre servicos). */
public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVED,
    PAYMENT_AUTHORIZED,
    CONFIRMED,
    CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED;
    }
}

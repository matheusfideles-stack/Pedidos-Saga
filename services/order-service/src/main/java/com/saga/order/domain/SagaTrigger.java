package com.saga.order.domain;

/** Eventos (ou comandos) que podem mover a maquina de estados do pedido. */
public enum SagaTrigger {
    INVENTORY_RESERVED,
    INVENTORY_REJECTED,
    PAYMENT_AUTHORIZED,
    PAYMENT_DECLINED,
    /** Interno: apos PAYMENT_AUTHORIZED o pedido e confirmado. */
    CONFIRM,
    CUSTOMER_CANCEL
}

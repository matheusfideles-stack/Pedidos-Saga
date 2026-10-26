package com.saga.order.domain;

/** Transicao que viola o dominio (ex.: cancelar pedido ja CONFIRMED). Mapeada para HTTP 409. */
public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(OrderStatus from, SagaTrigger trigger) {
        super("transicao invalida: " + trigger + " em " + from);
    }
}

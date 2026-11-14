package com.saga.order.domain;

import java.time.OffsetDateTime;

/**
 * Uma transicao da maquina de estados do pedido, na ordem em que aconteceu.
 * {@code from} e nulo apenas na criacao do pedido.
 */
public record StatusChange(OrderStatus from, OrderStatus to, String trigger, OffsetDateTime at) {
}

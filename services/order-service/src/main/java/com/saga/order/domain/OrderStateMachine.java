package com.saga.order.domain;

import static com.saga.order.domain.OrderStatus.CANCELLED;
import static com.saga.order.domain.OrderStatus.CONFIRMED;
import static com.saga.order.domain.OrderStatus.INVENTORY_RESERVED;
import static com.saga.order.domain.OrderStatus.PAYMENT_AUTHORIZED;
import static com.saga.order.domain.OrderStatus.PENDING;

import java.util.Optional;

/**
 * Maquina de estados do pedido, implementada manualmente (sem framework de saga).
 * Funcao pura: nao toca em banco nem em mensagens, por isso e totalmente testavel em unidade.
 *
 * <pre>
 *  PENDING --InventoryReserved--> INVENTORY_RESERVED --PaymentAuthorized--> PAYMENT_AUTHORIZED --confirm--> CONFIRMED
 *     |                                  |                                        |
 *     +--InventoryRejected / PaymentDeclined / CustomerCancel---------------------+--> CANCELLED
 * </pre>
 *
 * Eventos chegam de topicos diferentes e podem ser reordenados ou duplicados. Por isso:
 * <ul>
 *   <li>evento atrasado/duplicado em estado onde nao faz mais sentido = ignorado ({@link Optional#empty()});</li>
 *   <li>PaymentAuthorized pode chegar com o pedido ainda PENDING (antes do InventoryReserved ser lido): aceito;</li>
 *   <li>unico erro de dominio: cancelar um pedido ja CONFIRMED.</li>
 * </ul>
 */
public final class OrderStateMachine {

    /** @return novo estado, ou vazio se o evento deve ser ignorado (idempotente). */
    public static Optional<OrderStatus> next(OrderStatus current, SagaTrigger trigger) {
        return switch (current) {
            case PENDING -> switch (trigger) {
                case INVENTORY_RESERVED -> Optional.of(INVENTORY_RESERVED);
                case PAYMENT_AUTHORIZED -> Optional.of(PAYMENT_AUTHORIZED);
                case INVENTORY_REJECTED, PAYMENT_DECLINED, CUSTOMER_CANCEL -> Optional.of(CANCELLED);
                case CONFIRM -> Optional.empty();
            };
            case INVENTORY_RESERVED -> switch (trigger) {
                case PAYMENT_AUTHORIZED -> Optional.of(PAYMENT_AUTHORIZED);
                case PAYMENT_DECLINED, CUSTOMER_CANCEL -> Optional.of(CANCELLED);
                case INVENTORY_RESERVED, INVENTORY_REJECTED, CONFIRM -> Optional.empty();
            };
            case PAYMENT_AUTHORIZED -> switch (trigger) {
                case CONFIRM -> Optional.of(CONFIRMED);
                case CUSTOMER_CANCEL -> Optional.of(CANCELLED);
                case INVENTORY_RESERVED, INVENTORY_REJECTED, PAYMENT_AUTHORIZED, PAYMENT_DECLINED -> Optional.empty();
            };
            case CONFIRMED -> {
                if (trigger == SagaTrigger.CUSTOMER_CANCEL) {
                    throw new IllegalTransitionException(current, trigger);
                }
                yield Optional.empty();
            }
            case CANCELLED -> Optional.empty();
        };
    }

    private OrderStateMachine() {
    }
}

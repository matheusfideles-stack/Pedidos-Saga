package com.saga.order.domain;

import static com.saga.order.domain.OrderStatus.CANCELLED;
import static com.saga.order.domain.OrderStatus.CONFIRMED;
import static com.saga.order.domain.OrderStatus.INVENTORY_RESERVED;
import static com.saga.order.domain.OrderStatus.PAYMENT_AUTHORIZED;
import static com.saga.order.domain.OrderStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStateMachineTest {

    private static Optional<OrderStatus> next(OrderStatus s, SagaTrigger t) {
        return OrderStateMachine.next(s, t);
    }

    @Test
    @DisplayName("fluxo feliz: PENDING -> INVENTORY_RESERVED -> PAYMENT_AUTHORIZED -> CONFIRMED")
    void happyPath() {
        assertThat(next(PENDING, SagaTrigger.INVENTORY_RESERVED)).contains(INVENTORY_RESERVED);
        assertThat(next(INVENTORY_RESERVED, SagaTrigger.PAYMENT_AUTHORIZED)).contains(PAYMENT_AUTHORIZED);
        assertThat(next(PAYMENT_AUTHORIZED, SagaTrigger.CONFIRM)).contains(CONFIRMED);
    }

    @Test
    @DisplayName("falha de estoque cancela o pedido pendente")
    void inventoryRejected() {
        assertThat(next(PENDING, SagaTrigger.INVENTORY_REJECTED)).contains(CANCELLED);
    }

    @Test
    @DisplayName("pagamento recusado cancela (compensacao: libera estoque)")
    void paymentDeclined() {
        assertThat(next(INVENTORY_RESERVED, SagaTrigger.PAYMENT_DECLINED)).contains(CANCELLED);
        assertThat(next(PENDING, SagaTrigger.PAYMENT_DECLINED)).contains(CANCELLED);
    }

    @Test
    @DisplayName("PaymentAuthorized antes de InventoryReserved (topicos diferentes) e aceito")
    void paymentBeforeInventoryEvent() {
        assertThat(next(PENDING, SagaTrigger.PAYMENT_AUTHORIZED)).contains(PAYMENT_AUTHORIZED);
        // e o InventoryReserved tardio e descartado
        assertThat(next(PAYMENT_AUTHORIZED, SagaTrigger.INVENTORY_RESERVED)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED"})
    @DisplayName("cliente pode cancelar enquanto nao confirmado")
    void customerCancel(OrderStatus status) {
        assertThat(next(status, SagaTrigger.CUSTOMER_CANCEL)).contains(CANCELLED);
    }

    @Test
    @DisplayName("cancelar pedido CONFIRMED e erro de dominio")
    void cannotCancelConfirmed() {
        assertThatThrownBy(() -> next(CONFIRMED, SagaTrigger.CUSTOMER_CANCEL))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(SagaTrigger.class)
    @DisplayName("CANCELLED e absorvente: qualquer evento e ignorado (idempotencia)")
    void cancelledIsAbsorbing(SagaTrigger trigger) {
        assertThat(next(CANCELLED, trigger)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SagaTrigger.class, names = {"CUSTOMER_CANCEL"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CONFIRMED ignora eventos tardios ou duplicados")
    void confirmedIgnoresLateEvents(SagaTrigger trigger) {
        assertThat(next(CONFIRMED, trigger)).isEmpty();
    }

    @Test
    @DisplayName("eventos duplicados nao mudam o estado")
    void duplicatesAreNoOps() {
        assertThat(next(INVENTORY_RESERVED, SagaTrigger.INVENTORY_RESERVED)).isEmpty();
        assertThat(next(PAYMENT_AUTHORIZED, SagaTrigger.PAYMENT_AUTHORIZED)).isEmpty();
    }

    @Test
    @DisplayName("falha de estoque tardia nao desfaz pedido ja avancado")
    void lateInventoryRejection() {
        assertThat(next(INVENTORY_RESERVED, SagaTrigger.INVENTORY_REJECTED)).isEmpty();
        assertThat(next(PAYMENT_AUTHORIZED, SagaTrigger.INVENTORY_REJECTED)).isEmpty();
    }
}

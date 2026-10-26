package com.saga.order.service;

import java.util.UUID;

public final class ApiExceptions {

    /** Mesma Idempotency-Key com payload diferente: HTTP 422. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("Idempotency-Key '" + key + "' ja foi usada com um payload diferente");
        }
    }

    /** Requisicao original com a mesma chave ainda nao terminou: HTTP 409. */
    public static class IdempotencyInProgressException extends RuntimeException {
        public IdempotencyInProgressException(String key) {
            super("requisicao com Idempotency-Key '" + key + "' ainda em andamento");
        }
    }

    public static class UnknownProductException extends RuntimeException {
        public UnknownProductException(String productId) {
            super("produto inexistente ou inativo: " + productId);
        }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(UUID id) {
            super("pedido nao encontrado: " + id);
        }
    }

    private ApiExceptions() {
    }
}

package com.saga.common.events;

/** Mensagem que nunca sera processavel: vai direto para a DLT, sem retry. */
public class PoisonMessageException extends RuntimeException {
    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}

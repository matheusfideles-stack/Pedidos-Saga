package com.saga.payment.gateway;

/** Falha transitoria do gateway (5xx, timeout, conexao): unica classe de erro elegivel a retry. */
public class TransientGatewayException extends RuntimeException {
    public TransientGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}

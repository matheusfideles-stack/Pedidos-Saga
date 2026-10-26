package com.saga.common.events;

/** Nomes de topicos. O Debezium roteia por aggregatetype: {@code <aggregatetype>.events}. */
public final class Topics {
    public static final String ORDER_EVENTS = "order.events";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String CATALOG_EVENTS = "catalog.events";

    /** Valores de aggregatetype na tabela outbox. */
    public static final String AGG_ORDER = "order";
    public static final String AGG_INVENTORY = "inventory";
    public static final String AGG_PAYMENT = "payment";
    public static final String AGG_CATALOG = "catalog";

    /** Particoes por topico = teto de consumidores paralelos por grupo (ver ADR-005). */
    public static final int PARTITIONS = 6;

    public static String dlt(String topic) {
        return topic + ".DLT";
    }

    private Topics() {
    }
}

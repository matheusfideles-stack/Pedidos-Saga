CREATE TABLE payments (
    id             UUID           PRIMARY KEY,
    order_id       UUID           NOT NULL UNIQUE,   -- um pagamento por pedido: garante cobranca unica
    customer_id    VARCHAR(64)    NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    status         VARCHAR(16)    NOT NULL,          -- PENDING | AUTHORIZED | DECLINED | CANCELLED
    gateway_ref    VARCHAR(64),
    failure_reason TEXT,
    gateway_voided BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_status_updated ON payments (status, updated_at);

-- Estado do GATEWAY SIMULADO (um terceiro: aqui so para a demo ser stateless e consistente entre replicas).
CREATE TABLE gateway_charges (
    idempotency_key VARCHAR(64)    PRIMARY KEY,
    customer_id     VARCHAR(64)    NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    status          VARCHAR(16)    NOT NULL,         -- AUTHORIZED | DECLINED | VOIDED
    ref             VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

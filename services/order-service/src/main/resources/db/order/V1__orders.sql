CREATE TABLE products (
    id         VARCHAR(40)    PRIMARY KEY,
    name       TEXT           NOT NULL,
    price      NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    active     BOOLEAN        NOT NULL DEFAULT TRUE,
    version    BIGINT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id            UUID           PRIMARY KEY,
    customer_id   VARCHAR(64)    NOT NULL,
    status        VARCHAR(32)    NOT NULL,
    total         NUMERIC(12, 2) NOT NULL,
    currency      CHAR(3)        NOT NULL DEFAULT 'BRL',
    cancel_reason TEXT,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    order_id   UUID           NOT NULL REFERENCES orders (id),
    product_id VARCHAR(40)    NOT NULL,
    quantity   INT            NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

-- Trilha de auditoria da saga: cada transicao do pedido, com o evento que a causou.
CREATE TABLE order_status_history (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    UUID         NOT NULL REFERENCES orders (id),
    from_status VARCHAR(32),
    to_status   VARCHAR(32)  NOT NULL,
    trigger     VARCHAR(32)  NOT NULL,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_history_order ON order_status_history (order_id);

-- Idempotency-Key do POST /orders.
CREATE TABLE idempotency_keys (
    idem_key      VARCHAR(128) PRIMARY KEY,
    request_hash  CHAR(64)     NOT NULL,
    status_code   INT,
    response_body TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

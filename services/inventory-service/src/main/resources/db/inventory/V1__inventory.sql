CREATE TABLE stock (
    product_id VARCHAR(40) PRIMARY KEY,
    available  INT         NOT NULL CHECK (available >= 0),
    reserved   INT         NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Uma reserva por (pedido, produto). Status: RESERVED -> RELEASED (compensacao) | COMMITTED (pedido confirmado).
CREATE TABLE reservations (
    order_id   UUID        NOT NULL,
    product_id VARCHAR(40) NOT NULL REFERENCES stock (product_id),
    quantity   INT         NOT NULL CHECK (quantity > 0),
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, product_id)
);
CREATE INDEX idx_reservations_status ON reservations (status);

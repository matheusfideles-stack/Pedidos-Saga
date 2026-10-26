-- Tabelas de infraestrutura da saga, identicas em todos os servicos (cada um no seu proprio banco).

-- Transactional Outbox. Nomes de colunas seguem o padrao esperado pelo
-- io.debezium.transforms.outbox.EventRouter (aggregatetype / aggregateid / type / payload).
CREATE TABLE outbox (
    id            UUID         PRIMARY KEY,
    aggregatetype VARCHAR(64)  NOT NULL,
    aggregateid   VARCHAR(64)  NOT NULL,
    type          VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,
    traceparent   VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_created_at ON outbox (created_at);

-- Registro de eventos ja processados: gravado na MESMA transacao do efeito de negocio.
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

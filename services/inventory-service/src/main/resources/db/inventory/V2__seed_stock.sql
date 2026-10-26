-- Mesmos ids do catalogo do order-service. P-009 tem estoque baixo de proposito (cenario de falta de estoque).
INSERT INTO stock (product_id, available) VALUES
    ('P-001', 100000),
    ('P-002', 100000),
    ('P-003', 100000),
    ('P-004', 100000),
    ('P-005', 100000),
    ('P-006', 100000),
    ('P-007', 100000),
    ('P-008', 100000),
    ('P-009', 5),
    ('P-010', 100000);

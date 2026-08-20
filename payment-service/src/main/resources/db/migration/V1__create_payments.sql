CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    currency VARCHAR(3) NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_reference VARCHAR(100),
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_payments_order_id UNIQUE (order_id)
);

CREATE INDEX idx_payments_status ON payments (status);

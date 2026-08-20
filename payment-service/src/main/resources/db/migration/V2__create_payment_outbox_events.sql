CREATE TABLE payment_outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_key VARCHAR(400) NOT NULL UNIQUE,
    event_type VARCHAR(30) NOT NULL,
    order_id BIGINT NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_outbox_pending
    ON payment_outbox_events (status, next_attempt_at);

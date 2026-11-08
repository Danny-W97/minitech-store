ALTER TABLE payments ADD COLUMN checkout_session_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN checkout_url VARCHAR(2000);
ALTER TABLE payments ADD CONSTRAINT uk_payments_checkout_session UNIQUE (checkout_session_id);

CREATE TABLE stripe_webhook_receipts (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    payment_id BIGINT REFERENCES payments(id),
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('PROCESSED','IGNORED')),
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_stripe_webhook_payment ON stripe_webhook_receipts (payment_id, received_at DESC);

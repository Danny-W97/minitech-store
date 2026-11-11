CREATE TABLE payment_follow_ups (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES payments(order_id),
    kind VARCHAR(32) NOT NULL,
    payload TEXT,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    replay_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    processing_token VARCHAR(36),
    processing_until TIMESTAMPTZ,
    result_id BIGINT,
    last_error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_payment_follow_up_order_kind UNIQUE(order_id,kind),
    CONSTRAINT ck_payment_follow_up_status CHECK (status IN ('PENDING','WAITING','RETRY','SUCCEEDED','DEAD_LETTER')),
    CONSTRAINT ck_payment_follow_up_kind CHECK (kind IN ('PROMOTION_REDEMPTION','SHIPMENT_TRACKING','INVOICE_ISSUE','PAYMENT_NOTIFICATION'))
);
CREATE INDEX idx_payment_follow_up_due ON payment_follow_ups(next_attempt_at,id) WHERE status IN ('PENDING','RETRY');
CREATE INDEX idx_payment_follow_up_order ON payment_follow_ups(order_id,id);

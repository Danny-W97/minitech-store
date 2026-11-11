CREATE TABLE invoice_outbox_events (
 id BIGSERIAL PRIMARY KEY, event_id VARCHAR(128) NOT NULL UNIQUE, payload TEXT NOT NULL,
 status VARCHAR(20) NOT NULL, attempts INTEGER NOT NULL, last_error VARCHAR(500),
 next_attempt_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL, published_at TIMESTAMPTZ,
 processing_token VARCHAR(36), processing_until TIMESTAMPTZ
);
CREATE INDEX idx_invoice_outbox_due ON invoice_outbox_events(status,next_attempt_at,processing_until,id);

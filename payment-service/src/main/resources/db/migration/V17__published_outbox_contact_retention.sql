ALTER TABLE payment_outbox_events ADD COLUMN payload_compacted_at TIMESTAMPTZ;
ALTER TABLE payment_outbox_events ALTER COLUMN recipient DROP NOT NULL;
ALTER TABLE payment_outbox_events ADD CONSTRAINT ck_payment_outbox_payload_lifecycle CHECK (
    (recipient IS NOT NULL AND payload_compacted_at IS NULL)
    OR (recipient IS NULL AND delivery_address IS NULL AND payload_compacted_at IS NOT NULL
        AND status = 'PUBLISHED' AND published_at IS NOT NULL
        AND processing_token IS NULL AND processing_until IS NULL)
);
CREATE INDEX idx_payment_outbox_payload_retention ON payment_outbox_events
    (GREATEST(published_at, created_at), id)
    WHERE status = 'PUBLISHED' AND published_at IS NOT NULL AND recipient IS NOT NULL
        AND payload_compacted_at IS NULL AND processing_token IS NULL AND processing_until IS NULL;

ALTER TABLE invoice_outbox_events ADD COLUMN payload_compacted_at TIMESTAMPTZ;
ALTER TABLE invoice_outbox_events ALTER COLUMN payload DROP NOT NULL;
ALTER TABLE invoice_outbox_events ADD CONSTRAINT ck_invoice_outbox_payload_lifecycle CHECK (
    (payload IS NOT NULL AND payload_compacted_at IS NULL)
    OR (payload IS NULL AND payload_compacted_at IS NOT NULL AND status = 'PUBLISHED')
);
CREATE INDEX idx_invoice_outbox_payload_retention ON invoice_outbox_events
    (GREATEST(published_at, created_at), id)
    WHERE status = 'PUBLISHED' AND published_at IS NOT NULL AND payload IS NOT NULL
        AND payload_compacted_at IS NULL AND processing_token IS NULL AND processing_until IS NULL;

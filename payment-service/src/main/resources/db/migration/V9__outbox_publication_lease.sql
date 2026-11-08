ALTER TABLE payment_outbox_events ADD COLUMN processing_token VARCHAR(36);
ALTER TABLE payment_outbox_events ADD COLUMN processing_until TIMESTAMPTZ;
CREATE INDEX idx_payment_outbox_due_lease ON payment_outbox_events(status,next_attempt_at,processing_until,id);

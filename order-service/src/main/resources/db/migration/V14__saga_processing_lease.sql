ALTER TABLE order_sagas ADD COLUMN processing_token VARCHAR(36);
ALTER TABLE order_sagas ADD COLUMN processing_until TIMESTAMPTZ;
CREATE INDEX idx_sagas_processing_due ON order_sagas (state, next_attempt_at, processing_until, id);

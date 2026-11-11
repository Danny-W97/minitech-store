CREATE INDEX idx_payment_outbox_observability ON payment_outbox_events(status,created_at);
CREATE INDEX idx_follow_up_observability ON payment_follow_ups(status,created_at);
CREATE INDEX idx_paid_observability ON payments(status,result_recorded_at);

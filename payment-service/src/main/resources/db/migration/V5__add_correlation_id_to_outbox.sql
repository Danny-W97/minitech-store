ALTER TABLE payment_outbox_events
    ADD COLUMN correlation_id VARCHAR(100);

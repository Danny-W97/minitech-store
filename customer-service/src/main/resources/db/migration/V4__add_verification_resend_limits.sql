ALTER TABLE customers
    ADD COLUMN verification_resend_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN verification_resend_window_started_at TIMESTAMPTZ,
    ADD COLUMN verification_last_sent_at TIMESTAMPTZ;

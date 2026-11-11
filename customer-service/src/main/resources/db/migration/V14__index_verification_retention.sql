-- Clear markers in bounded batches; never delete customer accounts.
CREATE INDEX idx_customer_verification_expired_retention ON customers (verification_expires_at, id)
    WHERE enabled = FALSE AND verification_token_hash IS NOT NULL;

CREATE INDEX idx_customer_verification_confirmed_retention ON customers
    (COALESCE(GREATEST(verification_expires_at, verification_last_sent_at, verification_resend_window_started_at), updated_at), id)
    WHERE enabled = TRUE AND (verification_token_hash IS NOT NULL OR verification_expires_at IS NOT NULL
        OR verification_last_sent_at IS NOT NULL OR verification_resend_window_started_at IS NOT NULL
        OR verification_resend_count <> 0);

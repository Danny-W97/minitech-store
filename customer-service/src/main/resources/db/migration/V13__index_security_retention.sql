-- Retain complete refresh reuse evidence until the entire family's horizon has ended.
CREATE INDEX idx_refresh_retention_age ON refresh_tokens
    (GREATEST(expires_at, revoked_at, rotated_at), id);
CREATE INDEX idx_refresh_family_retention ON refresh_tokens
    (customer_id, family_id, GREATEST(expires_at, revoked_at, rotated_at));
CREATE INDEX idx_password_reset_retention ON password_reset_tokens
    (GREATEST(expires_at, used_at), id);
CREATE INDEX idx_recovery_used_retention ON customer_recovery_codes (used_at, id)
    WHERE used_at IS NOT NULL;
CREATE INDEX idx_totp_inactive_retention ON customer_totp_methods
    (COALESCE(enrollment_expires_at, updated_at), id) WHERE enabled = FALSE;

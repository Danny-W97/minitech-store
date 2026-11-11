ALTER TABLE audit_events ADD COLUMN subject_customer_id BIGINT CHECK (subject_customer_id > 0);
-- Retain evidence independently of account deletion; never infer old subjects from email.
CREATE INDEX idx_audit_subject_export ON audit_events(subject_customer_id, id DESC)
    WHERE subject_customer_id IS NOT NULL;
CREATE INDEX idx_audit_legacy_refresh_export ON audit_events(actor, id DESC)
    WHERE subject_customer_id IS NULL AND resource='REFRESH_TOKEN'
      AND ((event_type='TOKEN_REFRESH' AND outcome='SUCCESS')
        OR (event_type='REFRESH_TOKEN_REUSE' AND outcome='REJECTED'));

ALTER TABLE notifications ADD COLUMN content_compacted_at TIMESTAMPTZ;
ALTER TABLE notifications ALTER COLUMN subject DROP NOT NULL;
ALTER TABLE notifications ALTER COLUMN message DROP NOT NULL;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_content_lifecycle CHECK (
    (subject IS NOT NULL AND message IS NOT NULL AND content_compacted_at IS NULL)
    OR (subject IS NULL AND message IS NULL AND content_compacted_at IS NOT NULL
        AND status = 'SENT' AND sent_at IS NOT NULL AND next_attempt_at IS NULL
        AND failure_reason IS NULL AND processing_token IS NULL AND processing_until IS NULL)
);
CREATE INDEX idx_sent_notification_content_retention ON notifications
    (GREATEST(sent_at, created_at, updated_at), id)
    WHERE status = 'SENT' AND sent_at IS NOT NULL AND content_compacted_at IS NULL
        AND subject IS NOT NULL AND message IS NOT NULL AND next_attempt_at IS NULL
        AND failure_reason IS NULL AND processing_token IS NULL AND processing_until IS NULL;

ALTER TABLE notification_dead_letters ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE notification_dead_letters ADD COLUMN content_compacted_at TIMESTAMPTZ;
ALTER TABLE notification_dead_letters ALTER COLUMN subject DROP NOT NULL;
ALTER TABLE notification_dead_letters ALTER COLUMN message DROP NOT NULL;
ALTER TABLE notification_dead_letters ALTER COLUMN failure_reason DROP NOT NULL;
ALTER TABLE notification_dead_letters ADD CONSTRAINT ck_dead_letter_content_lifecycle CHECK (
    (subject IS NOT NULL AND message IS NOT NULL AND failure_reason IS NOT NULL AND content_compacted_at IS NULL)
    OR (subject IS NULL AND message IS NULL AND failure_reason IS NULL
        AND content_compacted_at IS NOT NULL AND status = 'RESOLVED' AND resolved_at IS NOT NULL)
);
CREATE INDEX idx_resolved_dead_letter_content_retention ON notification_dead_letters
    (GREATEST(resolved_at, created_at, updated_at), id)
    WHERE status = 'RESOLVED' AND resolved_at IS NOT NULL AND content_compacted_at IS NULL
        AND subject IS NOT NULL AND message IS NOT NULL AND failure_reason IS NOT NULL;

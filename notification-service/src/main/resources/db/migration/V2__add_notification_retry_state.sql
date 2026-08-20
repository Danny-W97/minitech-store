ALTER TABLE notifications
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE notifications
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_notifications_retry
    ON notifications (status, next_attempt_at);

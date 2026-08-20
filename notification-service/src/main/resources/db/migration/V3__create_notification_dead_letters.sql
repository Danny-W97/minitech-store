CREATE TABLE notification_dead_letters (
    id BIGSERIAL PRIMARY KEY,
    notification_id BIGINT NOT NULL UNIQUE,
    event_type VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(500) NOT NULL,
    attempt_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX idx_notification_dead_letters_status
    ON notification_dead_letters (status, created_at);

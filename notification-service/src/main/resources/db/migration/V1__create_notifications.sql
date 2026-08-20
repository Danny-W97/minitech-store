CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    channel VARCHAR(10) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_reference VARCHAR(100),
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT uk_notifications_event_source_recipient
        UNIQUE (event_type, source_id, recipient)
);

CREATE INDEX idx_notifications_source_id ON notifications (source_id);
CREATE INDEX idx_notifications_status ON notifications (status);

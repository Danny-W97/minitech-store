CREATE TABLE audit_events (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    resource VARCHAR(120) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    client_ip VARCHAR(64),
    user_agent VARCHAR(500),
    metadata VARCHAR(1000)
);

CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
CREATE INDEX idx_audit_events_event_type ON audit_events (event_type);
CREATE INDEX idx_audit_events_actor ON audit_events (actor);
CREATE INDEX idx_audit_events_correlation_id ON audit_events (correlation_id);

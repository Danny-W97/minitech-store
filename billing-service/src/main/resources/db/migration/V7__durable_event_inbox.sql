CREATE TABLE event_inbox (
 event_id VARCHAR(128) PRIMARY KEY, payload_hash VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL,
 related_id BIGINT, error VARCHAR(500), payload TEXT, received_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_event_inbox_status ON event_inbox(status,received_at);

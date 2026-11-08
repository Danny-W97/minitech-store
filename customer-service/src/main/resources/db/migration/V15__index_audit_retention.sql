-- The leading timestamp still serves existing audit-list queries.
CREATE INDEX idx_audit_events_retention ON audit_events (occurred_at, id);
DROP INDEX idx_audit_events_occurred_at;

-- Deterministic small retention batches; this prefix also serves the old time-only lookup.
CREATE INDEX idx_recommendation_requests_retention ON recommendation_requests (requested_at, id);
DROP INDEX idx_recommendation_requests_requested_at;

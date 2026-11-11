-- Pseudonymous compatibility links; never expire before the A43.40 policy.
CREATE TABLE event_identity_aliases (
 alias_id VARCHAR(128) PRIMARY KEY,
 canonical_id VARCHAR(128) NOT NULL
);
CREATE INDEX idx_event_alias_canonical ON event_identity_aliases(canonical_id);

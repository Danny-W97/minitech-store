-- Fixed UTF-8 encoding is part of the identity protocol, independent of client settings.
CREATE FUNCTION privacy_identity_hash(value TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT encode(sha256(convert_to(value,'UTF8')),'hex')
$$;
-- Existing v1 backlog keeps its protocol and identity. No replay or payload reconstruction.
ALTER TABLE payment_outbox_events ADD COLUMN stored_event_id VARCHAR(128);
ALTER TABLE payment_outbox_events ADD COLUMN identity_version INTEGER NOT NULL DEFAULT 1 CHECK(identity_version IN (1,2));
CREATE UNIQUE INDEX uk_payment_outbox_compatible_identity ON payment_outbox_events
 ((CASE WHEN event_key ~ '^(v2:|retained:)[0-9a-f]{64}$' THEN split_part(event_key,':',2)
    ELSE privacy_identity_hash(event_key) END));
CREATE UNIQUE INDEX uk_payment_outbox_stored_event_id ON payment_outbox_events(stored_event_id) WHERE stored_event_id IS NOT NULL;
ALTER TABLE payment_outbox_events ADD CONSTRAINT ck_payment_identity_v2 CHECK(identity_version=1 OR
 (stored_event_id IS NOT NULL AND stored_event_id ~ '^payment-v2-[0-9a-f-]{36}$' AND event_key ~ '^v2:[0-9a-f]{64}$'));

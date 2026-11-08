-- Explicit negative delivery only. Immutable, bounded history; parent restore quarantine blocks work.
CREATE TABLE commerce_retention_hold_delivery_attempts (
 request_id UUID NOT NULL,
 source VARCHAR(20) NOT NULL,
 attempt_no SMALLINT NOT NULL CHECK(attempt_no BETWEEN 1 AND 8),
 attempt_id UUID NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,source,attempt_no),
 FOREIGN KEY(request_id,source) REFERENCES commerce_retention_hold_source_requests(request_id,source)
);
CREATE TABLE commerce_retention_hold_delivery_receipts (
 request_id UUID NOT NULL,
 source VARCHAR(20) NOT NULL,
 source_request_id UUID NOT NULL REFERENCES commerce_retention_hold_source_requests(source_request_id),
 source_hold_id UUID NOT NULL,
 attempt_id UUID NOT NULL REFERENCES commerce_retention_hold_delivery_attempts(attempt_id),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,source),
 FOREIGN KEY(request_id,source) REFERENCES commerce_retention_hold_source_requests(request_id,source)
);
CREATE FUNCTION protect_retention_hold_delivery_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE quarantined BOOLEAN; next_attempt INT;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Hold delivery attempts are immutable' USING ERRCODE='23514'; END IF;
 SELECT restore_blocked INTO quarantined FROM commerce_retention_hold_requests WHERE request_id=NEW.request_id FOR UPDATE;
 IF NOT FOUND OR quarantined THEN RAISE EXCEPTION 'Hold delivery request unavailable' USING ERRCODE='23514'; END IF;
 SELECT coalesce(max(attempt_no),0)+1 INTO next_attempt FROM commerce_retention_hold_delivery_attempts WHERE request_id=NEW.request_id AND source=NEW.source;
 IF NEW.attempt_no<>next_attempt THEN RAISE EXCEPTION 'Hold delivery attempt sequence mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_delivery_attempt_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_delivery_attempts
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_delivery_attempt();
CREATE FUNCTION protect_retention_hold_delivery_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE quarantined BOOLEAN;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Hold delivery receipt history is immutable' USING ERRCODE='23514'; END IF;
 SELECT restore_blocked INTO quarantined FROM commerce_retention_hold_requests WHERE request_id=NEW.request_id FOR UPDATE;
 IF NOT FOUND OR quarantined THEN RAISE EXCEPTION 'Hold delivery request unavailable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM commerce_retention_hold_source_requests p WHERE p.request_id=NEW.request_id AND p.source=NEW.source AND p.source_request_id=NEW.source_request_id)
 OR NOT EXISTS(SELECT 1 FROM commerce_retention_hold_delivery_attempts a WHERE a.request_id=NEW.request_id AND a.source=NEW.source AND a.attempt_id=NEW.attempt_id) THEN
  RAISE EXCEPTION 'Hold delivery receipt scope mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_delivery_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_delivery_receipts
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_delivery_receipt();

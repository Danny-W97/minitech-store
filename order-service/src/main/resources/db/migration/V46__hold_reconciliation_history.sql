-- Immutable diagnostic history; not current preservation clearance or repair authority.
CREATE TABLE commerce_retention_hold_probes (
 request_id UUID NOT NULL,
 source VARCHAR(20) NOT NULL,
 probe_no SMALLINT NOT NULL CHECK(probe_no BETWEEN 1 AND 8),
 probe_id UUID NOT NULL UNIQUE,
 source_request_id UUID NOT NULL,
 source_hold_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,source,probe_no),
 FOREIGN KEY(request_id,source) REFERENCES commerce_retention_hold_delivery_receipts(request_id,source)
);
CREATE TABLE commerce_retention_hold_probe_results (
 probe_id UUID PRIMARY KEY REFERENCES commerce_retention_hold_probes(probe_id),
 code VARCHAR(32) NOT NULL CHECK(code IN ('MATCH_AS_OF_READ','MISSING_AT_SOURCE','SOURCE_CONFLICT','SOURCE_UNKNOWN','SOURCE_QUARANTINED','ROOT_QUARANTINED','NO_HISTORICAL_RECEIPT','UNKNOWN_REQUEST','UNAVAILABLE')),
 observed_at TIMESTAMPTZ,
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>=0),
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((code IN ('MATCH_AS_OF_READ','MISSING_AT_SOURCE','SOURCE_CONFLICT','SOURCE_UNKNOWN','SOURCE_QUARANTINED'))=(observed_at IS NOT NULL)),
 CHECK(CASE WHEN code IN ('MATCH_AS_OF_READ','MISSING_AT_SOURCE','SOURCE_CONFLICT','SOURCE_QUARANTINED') THEN binding_epoch>0
            ELSE binding_epoch=0 AND source_revision=0 END)
);
CREATE FUNCTION protect_retention_hold_probe() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE blocked BOOLEAN; subject BIGINT; next_probe INT;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Hold probe history is immutable' USING ERRCODE='23514'; END IF;
 SELECT order_id,restore_blocked INTO subject,blocked FROM commerce_retention_hold_requests WHERE request_id=NEW.request_id FOR UPDATE;
 IF NOT FOUND OR blocked THEN RAISE EXCEPTION 'Hold probe scope unavailable' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=subject) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=subject)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=subject AND restore_blocked) THEN
  RAISE EXCEPTION 'Hold probe scope quarantined' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM commerce_retention_hold_delivery_receipts r WHERE r.request_id=NEW.request_id AND r.source=NEW.source AND r.source_request_id=NEW.source_request_id AND r.source_hold_id=NEW.source_hold_id) THEN
  RAISE EXCEPTION 'Hold probe receipt mismatch' USING ERRCODE='23514'; END IF;
 SELECT coalesce(max(probe_no),0)+1 INTO next_probe FROM commerce_retention_hold_probes WHERE request_id=NEW.request_id AND source=NEW.source;
 IF NEW.probe_no<>next_probe THEN RAISE EXCEPTION 'Hold probe sequence mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_probe_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_probes
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_probe();
CREATE FUNCTION protect_retention_hold_probe_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent UUID; subject BIGINT; blocked BOOLEAN;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Hold probe results are immutable' USING ERRCODE='23514'; END IF;
 SELECT p.request_id INTO parent FROM commerce_retention_hold_probes p WHERE p.probe_id=NEW.probe_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'Hold probe absent' USING ERRCODE='23514'; END IF;
 SELECT r.order_id,r.restore_blocked INTO subject,blocked FROM commerce_retention_hold_requests r WHERE r.request_id=parent FOR UPDATE;
 IF blocked OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=subject) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=subject)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=subject AND restore_blocked) THEN
  IF NEW.code<>'ROOT_QUARANTINED' THEN RAISE EXCEPTION 'Quarantined hold probe result' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_probe_result_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_probe_results
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_probe_result();

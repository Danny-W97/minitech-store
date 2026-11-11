-- A diagnostic reservation binds one read to a checked review. It grants no write authority.
CREATE TABLE commerce_retention_hold_review_steps (
 step_id UUID PRIMARY KEY,
 review_id UUID NOT NULL REFERENCES commerce_retention_hold_review_versions(review_id),
 intent_id UUID NOT NULL REFERENCES commerce_retention_hold_repair_intents(intent_id),
 source VARCHAR(20) NOT NULL,
 probe_id UUID NOT NULL UNIQUE REFERENCES commerce_retention_hold_probes(probe_id),
 prepared_scope_hash CHAR(64) NOT NULL CHECK(prepared_scope_hash ~ '^[0-9a-f]{64}$'),
 prepared_history_hash CHAR(64) NOT NULL CHECK(prepared_history_hash ~ '^[0-9a-f]{64}$'),
 prepared_result_hash CHAR(64) NOT NULL CHECK(prepared_result_hash ~ '^[0-9a-f]{64}$'),
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(step_id=probe_id),
 UNIQUE(review_id,source)
);
CREATE FUNCTION protect_hold_review_step() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent UUID; subject BIGINT; blocked BOOLEAN;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Review steps are immutable' USING ERRCODE='23514'; END IF;
 SELECT request_id INTO parent FROM commerce_retention_hold_review_versions WHERE review_id=NEW.review_id;
 SELECT order_id,restore_blocked INTO subject,blocked FROM commerce_retention_hold_requests WHERE request_id=parent FOR UPDATE;
 IF NOT FOUND OR blocked OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=subject) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=subject)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=subject AND restore_blocked) THEN
  RAISE EXCEPTION 'Review step scope unavailable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS (
  SELECT 1 FROM commerce_retention_hold_review_versions v
  JOIN commerce_retention_hold_repair_intents i ON i.request_id=v.request_id
  JOIN commerce_retention_hold_probes p ON p.request_id=i.request_id AND p.source=i.source AND p.source_request_id=i.source_request_id AND p.source_hold_id=i.source_hold_id
  WHERE v.review_id=NEW.review_id AND i.intent_id=NEW.intent_id AND i.source=NEW.source AND p.probe_id=NEW.probe_id
   AND NEW.expires_at=v.expires_at AND NEW.prepared_scope_hash=v.scope_hash
   AND p.created_at>=v.reviewed_at AND p.created_at<v.expires_at
   AND v.reviewed_at<=clock_timestamp() AND v.expires_at>clock_timestamp()
   AND NOT EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_stops s WHERE s.intent_id=i.intent_id)
   AND NOT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_results r WHERE r.probe_id=p.probe_id)
   AND NOT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_resolutions x WHERE x.probe_id=p.probe_id)
 ) THEN RAISE EXCEPTION 'Review step binding or lifetime invalid' USING ERRCODE='23514'; END IF;
 NEW.created_at:=clock_timestamp();
 IF NEW.created_at< (SELECT reviewed_at FROM commerce_retention_hold_review_versions WHERE review_id=NEW.review_id) OR NEW.created_at>=NEW.expires_at THEN
  RAISE EXCEPTION 'Review step clock changed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER hold_review_step_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_review_steps FOR EACH ROW EXECUTE FUNCTION protect_hold_review_step();

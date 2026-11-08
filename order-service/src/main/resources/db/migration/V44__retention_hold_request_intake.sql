-- Trusted local negative intake only. No dispatch, authorization, release or case evidence.
CREATE TABLE commerce_retention_hold_requests (
 request_id UUID PRIMARY KEY,
 order_id BIGINT NOT NULL REFERENCES orders(id),
 case_reference UUID NOT NULL,
 guard VARCHAR(40) NOT NULL CHECK(guard IN ('NO_PRESERVATION_HOLD','NO_OPEN_OR_REOPENED_CASE','NO_ACTIVE_RETRY_OR_DLQ')),
 reason_code VARCHAR(24) NOT NULL CHECK(reason_code IN ('PRESERVATION_REQUEST','OPEN_CASE','DEPENDENCY_REVIEW')),
 root_hold_id UUID NOT NULL UNIQUE REFERENCES commerce_retention_holds(hold_id),
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE commerce_retention_hold_source_requests (
 request_id UUID NOT NULL REFERENCES commerce_retention_hold_requests(request_id),
 source VARCHAR(20) NOT NULL CHECK(source IN ('RETURNS','PAYMENT','INVENTORY','PROMOTION','DELIVERY','BILLING','NOTIFICATION')),
 source_request_id UUID NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(request_id,source)
);
CREATE INDEX idx_retention_hold_intake_quarantine ON commerce_retention_hold_requests(request_id) WHERE NOT restore_blocked;
CREATE FUNCTION protect_retention_hold_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Hold request history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.restore_blocked OR NOT EXISTS(SELECT 1 FROM commerce_retention_holds h WHERE h.hold_id=NEW.root_hold_id
    AND h.scope_type='ORDER' AND h.subject_id=NEW.order_id AND h.guard=NEW.guard AND h.reason_code=NEW.reason_code) THEN
   RAISE EXCEPTION 'Invalid local hold request binding' USING ERRCODE='23514';
  END IF;
 ELSIF (NEW.request_id,NEW.order_id,NEW.case_reference,NEW.guard,NEW.reason_code,NEW.root_hold_id,NEW.created_at)
  IS DISTINCT FROM (OLD.request_id,OLD.order_id,OLD.case_reference,OLD.guard,OLD.reason_code,OLD.root_hold_id,OLD.created_at)
  OR (OLD.restore_blocked AND NOT NEW.restore_blocked) THEN
  RAISE EXCEPTION 'Hold request identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_request_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_requests
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_request();
CREATE FUNCTION protect_retention_hold_source_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Source request identity is immutable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM commerce_retention_hold_requests r WHERE r.request_id=NEW.request_id AND NOT r.restore_blocked) THEN
  RAISE EXCEPTION 'Source request identity is immutable or quarantined' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_source_request_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_source_requests
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_source_request();
CREATE FUNCTION require_complete_retention_hold_source_plan() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (SELECT count(*) FROM commerce_retention_hold_source_requests s WHERE s.request_id=NEW.request_id)<>7 THEN
  RAISE EXCEPTION 'Incomplete source hold request plan' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER retention_hold_request_complete AFTER INSERT OR UPDATE ON commerce_retention_hold_requests
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_complete_retention_hold_source_plan();

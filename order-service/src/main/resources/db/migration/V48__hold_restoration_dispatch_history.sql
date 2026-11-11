-- Negative-hold restoration only. No authorization token, retry reset or cleanup authority.
CREATE TABLE commerce_retention_hold_restoration_attempts (
 intent_id UUID NOT NULL REFERENCES commerce_retention_hold_repair_intents(intent_id),
 attempt_id UUID PRIMARY KEY,
 attempt_no SMALLINT NOT NULL CHECK(attempt_no BETWEEN 1 AND 8),
 mode VARCHAR(16) NOT NULL CHECK(mode IN ('RESTORE','REPLAY_ONLY')),
 scope_hash CHAR(64) NOT NULL CHECK(scope_hash ~ '^[0-9a-f]{64}$'),
 case_authorization_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(intent_id,attempt_no)
);
CREATE TABLE commerce_retention_hold_restoration_results (
 attempt_id UUID PRIMARY KEY REFERENCES commerce_retention_hold_restoration_attempts(attempt_id),
 code VARCHAR(32) NOT NULL CHECK(code IN ('RESTORED','REPLAYED','AUTHORIZATION_REQUIRED','EXPIRED_AUTHORIZATION','UNKNOWN_PURCHASE','QUARANTINED','STALE_SOURCE','CONFLICT','REPLAY_UNAVAILABLE','ROOT_QUARANTINED','ROOT_STOPPED')),
 observed_at TIMESTAMPTZ,
 binding_epoch BIGINT NOT NULL DEFAULT 0,
 source_revision BIGINT NOT NULL DEFAULT 0,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK((code IN ('RESTORED','REPLAYED') AND observed_at IS NOT NULL AND binding_epoch>0 AND source_revision>0)
  OR (code NOT IN ('RESTORED','REPLAYED') AND binding_epoch=0 AND source_revision=0)),
 CHECK((code IN ('ROOT_QUARANTINED','ROOT_STOPPED'))=(observed_at IS NULL))
);
CREATE TABLE commerce_retention_hold_restoration_stops (
 intent_id UUID PRIMARY KEY REFERENCES commerce_retention_hold_repair_intents(intent_id),
 stop_id UUID NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION protect_hold_restoration_dispatch() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target UUID; parent UUID; subject BIGINT; blocked BOOLEAN; stopped BOOLEAN; next_no INT;
 first_attempt commerce_retention_hold_restoration_attempts%ROWTYPE;
 attempt commerce_retention_hold_restoration_attempts%ROWTYPE;
 epoch BIGINT; revision BIGINT; basis UUID;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Restoration history is immutable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='commerce_retention_hold_restoration_results' THEN
  SELECT intent_id INTO target FROM commerce_retention_hold_restoration_attempts WHERE attempt_id=NEW.attempt_id;
 ELSE target:=NEW.intent_id; END IF;
 SELECT request_id,basis_probe_id INTO parent,basis FROM commerce_retention_hold_repair_intents WHERE intent_id=target;
 SELECT order_id,restore_blocked INTO subject,blocked FROM commerce_retention_hold_requests WHERE request_id=parent FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Unknown restoration scope' USING ERRCODE='23514'; END IF;
 blocked:=blocked OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=subject) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=subject)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=subject AND restore_blocked);
 stopped:=EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_stops WHERE intent_id=target);
 IF TG_TABLE_NAME='commerce_retention_hold_restoration_stops' THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='commerce_retention_hold_restoration_attempts' THEN
  IF blocked OR stopped THEN RAISE EXCEPTION 'Restoration blocked' USING ERRCODE='23514'; END IF;
  SELECT coalesce(max(attempt_no),0)+1 INTO next_no FROM commerce_retention_hold_restoration_attempts WHERE intent_id=target;
  IF NEW.attempt_no<>next_no THEN RAISE EXCEPTION 'Restoration sequence mismatch' USING ERRCODE='23514'; END IF;
  IF next_no=1 THEN
   IF NEW.mode<>'RESTORE' OR NOT retention_hold_repair_candidate(basis) THEN RAISE EXCEPTION 'Restoration requires current missing basis' USING ERRCODE='23514'; END IF;
  ELSE
   SELECT * INTO first_attempt FROM commerce_retention_hold_restoration_attempts WHERE intent_id=target AND attempt_no=1;
   IF NEW.mode<>'REPLAY_ONLY' OR NEW.scope_hash<>first_attempt.scope_hash OR NEW.case_authorization_id<>first_attempt.case_authorization_id
    OR EXISTS(SELECT 1 FROM commerce_retention_hold_restoration_results r JOIN commerce_retention_hold_restoration_attempts a USING(attempt_id) WHERE a.intent_id=target AND r.code IN ('RESTORED','REPLAYED')) THEN
    RAISE EXCEPTION 'Only exact historical replay permitted' USING ERRCODE='23514'; END IF;
  END IF;
 ELSE
  SELECT * INTO attempt FROM commerce_retention_hold_restoration_attempts WHERE attempt_id=NEW.attempt_id;
  IF blocked AND NEW.code<>'ROOT_QUARANTINED' OR NOT blocked AND stopped AND NEW.code<>'ROOT_STOPPED'
   OR NOT blocked AND NOT stopped AND NEW.code IN ('ROOT_QUARANTINED','ROOT_STOPPED') THEN
   RAISE EXCEPTION 'Restoration result context mismatch' USING ERRCODE='23514'; END IF;
  IF NEW.code='RESTORED' AND attempt.mode<>'RESTORE' THEN RAISE EXCEPTION 'Replay cannot acknowledge new write' USING ERRCODE='23514'; END IF;
  IF NEW.code IN ('RESTORED','REPLAYED') THEN
   SELECT binding_epoch,source_revision INTO epoch,revision FROM commerce_retention_hold_probe_results WHERE probe_id=basis;
   IF NEW.binding_epoch<>epoch OR NEW.source_revision<=revision THEN RAISE EXCEPTION 'Restoration result version mismatch' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER hold_restoration_attempt_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_restoration_attempts FOR EACH ROW EXECUTE FUNCTION protect_hold_restoration_dispatch();
CREATE TRIGGER hold_restoration_result_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_restoration_results FOR EACH ROW EXECUTE FUNCTION protect_hold_restoration_dispatch();
CREATE TRIGGER hold_restoration_stop_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_restoration_stops FOR EACH ROW EXECUTE FUNCTION protect_hold_restoration_dispatch();

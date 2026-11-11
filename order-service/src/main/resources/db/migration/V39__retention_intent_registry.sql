-- Preparation/stop history only. No AUTHORIZED, EXECUTING or COMPLETED state.
CREATE TABLE commerce_retention_intents (
 intent_id UUID PRIMARY KEY,
 order_id BIGINT NOT NULL,
 generation UUID NOT NULL,
 order_version BIGINT NOT NULL CHECK(order_version>=0),
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('REDACTION','MINIMUM_REMOVAL')),
 policy VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'WAITING_AUTHORIZATION'
   CHECK(state IN ('WAITING_AUTHORIZATION','STOP_REQUESTED','CANCELLED','QUARANTINED')),
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 3),
 prepared_proof_revision BIGINT NOT NULL CHECK(prepared_proof_revision>=0),
 order_hold_revision BIGINT NOT NULL CHECK(order_hold_revision>=0),
 customer_hold_revision BIGINT NOT NULL CHECK(customer_hold_revision>=0),
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 last_request_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(order_id,generation,phase),
 FOREIGN KEY(order_id,generation,phase) REFERENCES commerce_retention_jobs(order_id,generation,phase),
 CHECK(restore_blocked=(state='QUARANTINED'))
);
CREATE TABLE commerce_retention_intent_events (
 intent_id UUID NOT NULL REFERENCES commerce_retention_intents(intent_id),
 revision BIGINT NOT NULL CHECK(revision BETWEEN 0 AND 3),
 request_id UUID NOT NULL UNIQUE,
 state VARCHAR(32) NOT NULL CHECK(state IN ('WAITING_AUTHORIZATION','STOP_REQUESTED','CANCELLED','QUARANTINED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(intent_id,revision)
);
CREATE FUNCTION protect_retention_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retention intent history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'WAITING_AUTHORIZATION' OR NEW.revision<>0 OR NEW.restore_blocked OR NEW.last_request_id<>NEW.intent_id
   OR NOT EXISTS(SELECT 1 FROM commerce_retention_jobs j WHERE j.order_id=NEW.order_id AND j.generation=NEW.generation
        AND j.phase=NEW.phase AND j.order_version=NEW.order_version AND j.policy=NEW.policy) THEN
   RAISE EXCEPTION 'Invalid retention intent preparation' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.intent_id,NEW.order_id,NEW.generation,NEW.order_version,NEW.phase,NEW.policy,
      NEW.prepared_proof_revision,NEW.order_hold_revision,NEW.customer_hold_revision,NEW.created_at)
    IS DISTINCT FROM (OLD.intent_id,OLD.order_id,OLD.generation,OLD.order_version,OLD.phase,OLD.policy,
      OLD.prepared_proof_revision,OLD.order_hold_revision,OLD.customer_hold_revision,OLD.created_at)
    OR OLD.restore_blocked OR NEW.revision<>OLD.revision+1 OR NEW.last_request_id=OLD.last_request_id
    OR NOT ((OLD.state='WAITING_AUTHORIZATION' AND NEW.state IN ('STOP_REQUESTED','CANCELLED'))
      OR (OLD.state='STOP_REQUESTED' AND NEW.state='CANCELLED')
      OR (OLD.state<>'QUARANTINED' AND NEW.state='QUARANTINED' AND NEW.restore_blocked)) THEN
   RAISE EXCEPTION 'Invalid retention intent transition' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_intent_protocol BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_intents
 FOR EACH ROW EXECUTE FUNCTION protect_retention_intent();
CREATE FUNCTION protect_retention_intent_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Retention intent events are immutable' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM commerce_retention_intents i WHERE i.intent_id=NEW.intent_id
   AND i.revision=NEW.revision AND i.last_request_id=NEW.request_id AND i.state=NEW.state) THEN
  RAISE EXCEPTION 'Retention intent event scope mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_intent_event_protocol BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_intent_events
 FOR EACH ROW EXECUTE FUNCTION protect_retention_intent_event();
CREATE FUNCTION append_retention_intent_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO commerce_retention_intent_events(intent_id,revision,request_id,state)
  VALUES(NEW.intent_id,NEW.revision,NEW.last_request_id,NEW.state);
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_intent_event AFTER INSERT OR UPDATE ON commerce_retention_intents
 FOR EACH ROW EXECUTE FUNCTION append_retention_intent_event();
CREATE INDEX idx_retention_intents_restore ON commerce_retention_intents(intent_id) WHERE NOT restore_blocked;

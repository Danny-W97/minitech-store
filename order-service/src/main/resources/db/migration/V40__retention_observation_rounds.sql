-- Diagnostic write-ahead records only. No authorization, resume or age-based expiry.
CREATE TABLE commerce_retention_observation_rounds (
 round_id UUID PRIMARY KEY,
 intent_id UUID NOT NULL REFERENCES commerce_retention_intents(intent_id),
 intent_revision BIGINT NOT NULL CHECK(intent_revision>=0),
 ordinal SMALLINT NOT NULL CHECK(ordinal BETWEEN 1 AND 8),
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 finished_at TIMESTAMPTZ,
 UNIQUE(intent_id,ordinal)
);
CREATE UNIQUE INDEX idx_retention_observation_open ON commerce_retention_observation_rounds(intent_id)
 WHERE finished_at IS NULL AND NOT restore_blocked;
CREATE TABLE commerce_retention_observation_leases (
 round_id UUID NOT NULL REFERENCES commerce_retention_observation_rounds(round_id),
 source VARCHAR(20) NOT NULL CHECK(source IN ('PAYMENT','INVENTORY','RETURNS','PROMOTION','DELIVERY','BILLING','NOTIFICATION')),
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 release_confirmed_at TIMESTAMPTZ,
 PRIMARY KEY(round_id,source)
);
CREATE FUNCTION protect_retention_observation_round() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i commerce_retention_intents%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Observation history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  SELECT * INTO i FROM commerce_retention_intents WHERE intent_id=NEW.intent_id FOR UPDATE;
  IF NOT FOUND OR i.restore_blocked OR i.state<>'WAITING_AUTHORIZATION' OR i.revision<>NEW.intent_revision
   OR NEW.restore_blocked OR NEW.finished_at IS NOT NULL
   OR NEW.ordinal<>(SELECT coalesce(max(ordinal),0)+1 FROM commerce_retention_observation_rounds WHERE intent_id=NEW.intent_id)
   OR EXISTS(SELECT 1 FROM commerce_retention_observation_rounds WHERE intent_id=NEW.intent_id AND finished_at IS NULL)
   THEN RAISE EXCEPTION 'Invalid observation round' USING ERRCODE='23514'; END IF;
 ELSE
  IF OLD.restore_blocked OR (NEW.round_id,NEW.intent_id,NEW.intent_revision,NEW.ordinal,NEW.created_at)
      IS DISTINCT FROM (OLD.round_id,OLD.intent_id,OLD.intent_revision,OLD.ordinal,OLD.created_at)
   OR NOT ((NEW.restore_blocked AND NOT OLD.restore_blocked AND NEW.finished_at IS NOT DISTINCT FROM OLD.finished_at)
      OR (NOT NEW.restore_blocked AND OLD.finished_at IS NULL AND NEW.finished_at IS NOT NULL
        AND NOT EXISTS(SELECT 1 FROM commerce_retention_observation_leases WHERE round_id=OLD.round_id AND release_confirmed_at IS NULL)))
   THEN RAISE EXCEPTION 'Invalid observation round transition' USING ERRCODE='23514'; END IF;
  IF NEW.finished_at IS DISTINCT FROM OLD.finished_at THEN NEW.finished_at=clock_timestamp(); END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER retention_observation_round_protocol BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_observation_rounds
 FOR EACH ROW EXECUTE FUNCTION protect_retention_observation_round();
CREATE FUNCTION protect_retention_observation_lease() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r commerce_retention_observation_rounds%ROWTYPE; i commerce_retention_intents%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Observation lease history is retained' USING ERRCODE='23514'; END IF;
 SELECT * INTO r FROM commerce_retention_observation_rounds WHERE round_id=NEW.round_id FOR UPDATE;
 SELECT * INTO i FROM commerce_retention_intents WHERE intent_id=r.intent_id;
 IF r.round_id IS NULL OR r.restore_blocked OR r.finished_at IS NOT NULL OR i.restore_blocked
  THEN RAISE EXCEPTION 'Observation round is unavailable' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.release_confirmed_at IS NOT NULL OR i.state<>'WAITING_AUTHORIZATION' OR i.revision<>r.intent_revision
   OR NOT EXISTS(SELECT 1 FROM commerce_retention_bindings b WHERE b.order_id=i.order_id AND b.generation=i.generation
    AND b.order_version=i.order_version AND b.phase=i.phase AND b.policy=i.policy AND b.source=NEW.source
    AND b.bound_epoch=NEW.binding_epoch AND b.source_revision=NEW.source_revision AND b.state='BOUND'
    AND NOT b.restore_blocked AND NOT b.invalidated)
   THEN RAISE EXCEPTION 'Invalid observation lease scope' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.round_id,NEW.source,NEW.binding_epoch,NEW.source_revision,NEW.recorded_at)
      IS DISTINCT FROM (OLD.round_id,OLD.source,OLD.binding_epoch,OLD.source_revision,OLD.recorded_at)
   OR OLD.release_confirmed_at IS NOT NULL OR NEW.release_confirmed_at IS NULL
   THEN RAISE EXCEPTION 'Invalid observation lease transition' USING ERRCODE='23514'; END IF;
  NEW.release_confirmed_at=clock_timestamp();
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER retention_observation_lease_protocol BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_observation_leases
 FOR EACH ROW EXECUTE FUNCTION protect_retention_observation_lease();
CREATE INDEX idx_retention_observation_restore ON commerce_retention_observation_rounds(round_id) WHERE NOT restore_blocked;

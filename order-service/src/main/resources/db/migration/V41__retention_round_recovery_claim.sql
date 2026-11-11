-- Explicit release-only recovery. Never resumes source PREPARE or business mutation.
ALTER TABLE commerce_retention_observation_rounds ADD COLUMN cleanup_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE commerce_retention_observation_rounds ADD COLUMN recovery_token UUID;
ALTER TABLE commerce_retention_observation_rounds ADD COLUMN recovery_until TIMESTAMPTZ;
ALTER TABLE commerce_retention_observation_rounds ADD COLUMN recovery_attempts SMALLINT NOT NULL DEFAULT 0 CHECK(recovery_attempts BETWEEN 0 AND 8);
ALTER TABLE commerce_retention_observation_rounds ADD CONSTRAINT observation_recovery_pair
 CHECK((recovery_token IS NULL)=(recovery_until IS NULL));
ALTER TABLE commerce_retention_observation_rounds ADD CONSTRAINT observation_recovery_mode
 CHECK((cleanup_only OR recovery_token IS NULL) AND (cleanup_only OR recovery_attempts=0));

CREATE FUNCTION retention_observation_recovery_owner(token UUID,deadline TIMESTAMPTZ) RETURNS BOOLEAN LANGUAGE sql AS $$
 SELECT token IS NOT NULL AND deadline>clock_timestamp()
  AND token::text=coalesce(current_setting('app.retention_recovery_token',TRUE),'')
$$;
CREATE OR REPLACE FUNCTION protect_retention_observation_round() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE i commerce_retention_intents%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Observation history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  SELECT * INTO i FROM commerce_retention_intents WHERE intent_id=NEW.intent_id FOR UPDATE;
  IF NOT FOUND OR i.restore_blocked OR i.state<>'WAITING_AUTHORIZATION' OR i.revision<>NEW.intent_revision
   OR NEW.restore_blocked OR NEW.finished_at IS NOT NULL OR NEW.cleanup_only OR NEW.recovery_token IS NOT NULL
   OR NEW.recovery_until IS NOT NULL OR NEW.recovery_attempts<>0
   OR NEW.ordinal<>(SELECT coalesce(max(ordinal),0)+1 FROM commerce_retention_observation_rounds WHERE intent_id=NEW.intent_id)
   OR EXISTS(SELECT 1 FROM commerce_retention_observation_rounds WHERE intent_id=NEW.intent_id AND finished_at IS NULL)
   THEN RAISE EXCEPTION 'Invalid observation round' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF OLD.restore_blocked OR (NEW.round_id,NEW.intent_id,NEW.intent_revision,NEW.ordinal,NEW.created_at)
   IS DISTINCT FROM (OLD.round_id,OLD.intent_id,OLD.intent_revision,OLD.ordinal,OLD.created_at)
  THEN RAISE EXCEPTION 'Invalid observation identity' USING ERRCODE='23514'; END IF;
 IF NEW.restore_blocked AND NEW.finished_at IS NOT DISTINCT FROM OLD.finished_at
   AND NEW.cleanup_only=OLD.cleanup_only AND NEW.recovery_attempts=OLD.recovery_attempts THEN
  NEW.recovery_token=NULL; NEW.recovery_until=NULL; RETURN NEW;
 END IF;
 IF NEW.restore_blocked OR OLD.finished_at IS NOT NULL OR (OLD.cleanup_only AND NOT NEW.cleanup_only)
  THEN RAISE EXCEPTION 'Observation round is unavailable' USING ERRCODE='23514'; END IF;
 IF NEW.cleanup_only AND NEW.recovery_token IS NOT NULL AND NEW.recovery_token IS DISTINCT FROM OLD.recovery_token
   AND NEW.recovery_attempts=OLD.recovery_attempts+1 AND NEW.finished_at IS NULL
   AND (OLD.recovery_token IS NULL OR OLD.recovery_until<=clock_timestamp()) THEN
  NEW.recovery_until=clock_timestamp()+INTERVAL '15 seconds'; RETURN NEW;
 END IF;
 IF NEW.cleanup_only<>OLD.cleanup_only OR NEW.recovery_attempts<>OLD.recovery_attempts
   OR NEW.recovery_token IS NOT NULL OR NEW.recovery_until IS NOT NULL
   OR (OLD.cleanup_only AND NOT retention_observation_recovery_owner(OLD.recovery_token,OLD.recovery_until))
  THEN RAISE EXCEPTION 'Invalid observation recovery owner' USING ERRCODE='23514'; END IF;
 IF NEW.finished_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM commerce_retention_observation_leases WHERE round_id=OLD.round_id AND release_confirmed_at IS NULL) THEN
  NEW.finished_at=clock_timestamp(); RETURN NEW;
 END IF;
 IF OLD.cleanup_only AND OLD.recovery_token IS NOT NULL AND NEW.finished_at IS NULL THEN RETURN NEW; END IF;
 RAISE EXCEPTION 'Invalid observation round transition' USING ERRCODE='23514';
END; $$;

CREATE OR REPLACE FUNCTION protect_retention_observation_lease() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r commerce_retention_observation_rounds%ROWTYPE; i commerce_retention_intents%ROWTYPE;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Observation lease history is retained' USING ERRCODE='23514'; END IF;
 SELECT * INTO r FROM commerce_retention_observation_rounds WHERE round_id=NEW.round_id FOR UPDATE;
 SELECT * INTO i FROM commerce_retention_intents WHERE intent_id=r.intent_id;
 IF r.round_id IS NULL OR r.restore_blocked OR r.finished_at IS NOT NULL OR i.restore_blocked
  THEN RAISE EXCEPTION 'Observation round is unavailable' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF r.cleanup_only OR NEW.release_confirmed_at IS NOT NULL OR i.state<>'WAITING_AUTHORIZATION' OR i.revision<>r.intent_revision
   OR NOT EXISTS(SELECT 1 FROM commerce_retention_bindings b WHERE b.order_id=i.order_id AND b.generation=i.generation
    AND b.order_version=i.order_version AND b.phase=i.phase AND b.policy=i.policy AND b.source=NEW.source
    AND b.bound_epoch=NEW.binding_epoch AND b.source_revision=NEW.source_revision AND b.state='BOUND'
    AND NOT b.restore_blocked AND NOT b.invalidated)
   THEN RAISE EXCEPTION 'Invalid observation lease scope' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.round_id,NEW.source,NEW.binding_epoch,NEW.source_revision,NEW.recorded_at)
      IS DISTINCT FROM (OLD.round_id,OLD.source,OLD.binding_epoch,OLD.source_revision,OLD.recorded_at)
   OR OLD.release_confirmed_at IS NOT NULL OR NEW.release_confirmed_at IS NULL
   OR (r.cleanup_only AND NOT retention_observation_recovery_owner(r.recovery_token,r.recovery_until))
   THEN RAISE EXCEPTION 'Invalid observation lease acknowledgement' USING ERRCODE='23514'; END IF;
  NEW.release_confirmed_at=clock_timestamp();
 END IF;
 RETURN NEW;
END; $$;

-- Historical source observations, not erasure receipts or current clearance.
-- At most six rows per job generation. No content, customer identifier or token.
CREATE TABLE commerce_retention_source_receipts (
 order_id BIGINT NOT NULL,
 generation UUID NOT NULL,
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('REDACTION','MINIMUM_REMOVAL')),
 source VARCHAR(20) NOT NULL CHECK(source IN ('RETURNS','PAYMENT','PROMOTION','DELIVERY','BILLING','NOTIFICATION')),
 order_version BIGINT NOT NULL CHECK(order_version>=0),
 policy VARCHAR(64) NOT NULL,
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 receipt_id UUID NOT NULL UNIQUE,
 observation_hash VARCHAR(64) NOT NULL CHECK(observation_hash ~ '^[0-9a-f]{64}$'),
 case_state VARCHAR(10) NOT NULL CHECK(case_state IN ('UNKNOWN','BLOCKED')),
 retry_state VARCHAR(10) NOT NULL CHECK(retry_state IN ('UNKNOWN','BLOCKED')),
 observed_at TIMESTAMPTZ NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(order_id,generation,phase,source),
 FOREIGN KEY(order_id,generation,phase) REFERENCES commerce_retention_jobs(order_id,generation,phase),
 CHECK(observed_at>=recorded_at-interval '15 seconds' AND observed_at<=recorded_at+interval '5 seconds')
);
CREATE FUNCTION protect_retention_source_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Source observation receipts are immutable' USING ERRCODE='23514'; END IF;
 -- Static scope only: historical/retired jobs must remain restorable.
 IF NOT EXISTS(SELECT 1 FROM commerce_retention_jobs j WHERE j.order_id=NEW.order_id
    AND j.generation=NEW.generation AND j.phase=NEW.phase
    AND j.order_version=NEW.order_version AND j.policy=NEW.policy) THEN
  RAISE EXCEPTION 'Source observation receipt scope mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_source_receipt_scope BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_source_receipts
 FOR EACH ROW EXECUTE FUNCTION protect_retention_source_receipt();

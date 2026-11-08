-- Durable negative-invalidation coordination only; not reconciliation evidence.
CREATE TABLE commerce_retention_bindings (
 order_id BIGINT NOT NULL,
 generation UUID NOT NULL,
 source VARCHAR(32) NOT NULL CHECK(source IN ('RETURNS','PAYMENT','INVENTORY','PROMOTION','DELIVERY','BILLING','NOTIFICATION')),
 order_version BIGINT NOT NULL CHECK(order_version>=0),
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('REDACTION','MINIMUM_REMOVAL')),
 policy VARCHAR(64) NOT NULL CHECK(policy='D02-P02-2026-10-30.1'),
 expected_epoch BIGINT NOT NULL CHECK(expected_epoch>=0 AND expected_epoch<9223372036854775807),
 state VARCHAR(8) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','BOUND','FAILED')),
 attempts INT NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 run_token UUID,
 run_until TIMESTAMPTZ,
 retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 bound_epoch BIGINT,
 source_revision BIGINT,
 invalidated BOOLEAN,
 code VARCHAR(24) CHECK(code IS NULL OR code IN ('TRANSPORT_FAILED','RETRY_LIMIT')),
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(order_id,generation,source),
 UNIQUE(order_id,source,expected_epoch),
 FOREIGN KEY(order_id,generation,phase) REFERENCES commerce_retention_jobs(order_id,generation,phase),
 CHECK((run_token IS NULL)=(run_until IS NULL)),
 CHECK((state='LEASED')=(run_token IS NOT NULL)),
 CHECK((state='BOUND')=(bound_epoch IS NOT NULL)),
 CHECK((state='BOUND')=(source_revision IS NOT NULL)),
 CHECK((state='BOUND')=(invalidated IS NOT NULL)),
 CHECK(bound_epoch IS NULL OR bound_epoch=expected_epoch+1),
 CHECK(source_revision IS NULL OR source_revision>=0)
);
CREATE INDEX idx_retention_binding_due ON commerce_retention_bindings(retry_at,order_id,source)
 WHERE NOT restore_blocked AND state IN ('PENDING','LEASED','FAILED');

CREATE FUNCTION protect_commerce_retention_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retention binding history is retained' USING ERRCODE='23514'; END IF;
 IF (NEW.order_id,NEW.generation,NEW.source,NEW.order_version,NEW.phase,NEW.policy,NEW.expected_epoch,NEW.created_at)
  IS DISTINCT FROM (OLD.order_id,OLD.generation,OLD.source,OLD.order_version,OLD.phase,OLD.policy,OLD.expected_epoch,OLD.created_at)
  OR (OLD.restore_blocked AND NOT NEW.restore_blocked)
  OR (OLD.state='BOUND' AND (NEW.state,NEW.bound_epoch,NEW.source_revision,NEW.invalidated)
      IS DISTINCT FROM (OLD.state,OLD.bound_epoch,OLD.source_revision,OLD.invalidated)) THEN
  RAISE EXCEPTION 'Retention binding command and receipt are immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_binding_protocol BEFORE UPDATE OR DELETE ON commerce_retention_bindings
 FOR EACH ROW EXECUTE FUNCTION protect_commerce_retention_binding();

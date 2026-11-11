-- D02: durable assessment metadata only. No business-field redaction or TTL.
CREATE TABLE commerce_retention_jobs (
 order_id BIGINT NOT NULL REFERENCES orders(id),
 generation UUID NOT NULL,
 customer_id BIGINT,
 order_version BIGINT NOT NULL CHECK(order_version>=0),
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('REDACTION','MINIMUM_REMOVAL')),
 policy VARCHAR(64) NOT NULL,
 current BOOLEAN NOT NULL DEFAULT TRUE,
 state VARCHAR(24) NOT NULL DEFAULT 'WAITING_PROOF' CHECK(state IN ('WAITING_PROOF','WAITING_AGE','LEASED','BLOCKED','REVIEW','DUE_FOR_REVIEW','FAILED','STALE')),
 proof_revision BIGINT NOT NULL DEFAULT 0 CHECK(proof_revision>=0),
 attempts INT NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 run_token UUID,
 run_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 eligible_at TIMESTAMPTZ,
 code VARCHAR(48),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(order_id,generation),
 UNIQUE(order_id,generation,phase),
 CHECK(customer_id IS NULL OR customer_id>0),
 CHECK((run_token IS NULL)=(run_until IS NULL)),
 CHECK((state='LEASED')=(run_token IS NOT NULL)),
 CHECK((state='STALE')=(NOT current)),
 CHECK(code IS NULL OR code IN ('AGE_REACHED','AGE_NOT_REACHED','MISSING_CHAIN_PROOF','DUPLICATE_PARTICIPANT','CONFLICTING_PROOF','UNRECONCILED_PROCESS','MISSING_CLOCK','FUTURE_CLOCK','MISSING_GUARD_PROOF','PRESERVATION_OR_DEPENDENCY_BLOCK','MISSING_REDACTION_PROOF','REDACTION_BEFORE_RECONCILIATION','CLOCK_RANGE','ORDER_CHANGED','EVIDENCE_CHANGED','SOURCE_INVALIDATED','RETRY_LIMIT','ASSESSMENT_FAILED'))
);
CREATE UNIQUE INDEX idx_retention_current_order ON commerce_retention_jobs(order_id) WHERE current;
CREATE INDEX idx_retention_assessment_due ON commerce_retention_jobs(next_attempt_at,order_id) WHERE current AND state IN ('WAITING_PROOF','WAITING_AGE','LEASED','FAILED');

CREATE TABLE commerce_retention_evidence (
 order_id BIGINT NOT NULL,
 generation UUID NOT NULL,
 phase VARCHAR(20) NOT NULL,
 kind VARCHAR(5) NOT NULL CHECK(kind IN ('CHAIN','GUARD')),
 source VARCHAR(40) NOT NULL,
 policy VARCHAR(64) NOT NULL,
 state VARCHAR(10) NOT NULL CHECK(state IN ('CONFIRMED','BLOCKED','UNKNOWN')),
 reconciled_at TIMESTAMPTZ,
 redacted_at TIMESTAMPTZ,
 PRIMARY KEY(order_id,generation,phase,kind,source),
 FOREIGN KEY(order_id,generation,phase) REFERENCES commerce_retention_jobs(order_id,generation,phase),
 CHECK((kind='CHAIN' AND source IN ('ORDER','RETURNS','PAYMENT','INVENTORY','PROMOTION','DELIVERY','BILLING','NOTIFICATION'))
    OR (kind='GUARD' AND source IN ('FIELD_SCOPE_REVIEWED','PRESERVATION_POLICY_REVIEWED','NO_PRESERVATION_HOLD','NO_OPEN_OR_REOPENED_CASE','NO_ACTIVE_RETRY_OR_DLQ','INDEPENDENT_JOURNAL_CURRENT','FINANCIAL_DEPENDENCIES_RELEASED','RESTORE_DEPENDENCIES_RELEASED','RETRY_DEPENDENCIES_RELEASED','DEDUP_DEPENDENCIES_RELEASED'))),
 CHECK(kind='CHAIN' OR (reconciled_at IS NULL AND redacted_at IS NULL))
);

-- Existing order-first locking makes invalidation and assessment serializable
-- for these local rows. Remote services still require their own versioned hooks.
CREATE FUNCTION invalidate_commerce_retention() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='orders' THEN
  IF TG_OP<>'INSERT' THEN previous:=OLD.id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.id; END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 END IF;
 UPDATE commerce_retention_jobs SET current=FALSE,state='STALE',code='ORDER_CHANGED',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
 WHERE current AND order_id IN (previous,subject);
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_order_change AFTER UPDATE OR DELETE ON orders FOR EACH ROW EXECUTE FUNCTION invalidate_commerce_retention();
CREATE TRIGGER retention_item_change AFTER INSERT OR UPDATE OR DELETE ON order_items FOR EACH ROW EXECUTE FUNCTION invalidate_commerce_retention();
CREATE TRIGGER retention_saga_change AFTER INSERT OR UPDATE OR DELETE ON order_sagas FOR EACH ROW EXECUTE FUNCTION invalidate_commerce_retention();

-- D02 source binding and transactional invalidation; no business-data expiry.
CREATE TABLE commerce_retention_source_state (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 generation UUID NOT NULL,
 order_version BIGINT NOT NULL CHECK(order_version>=0),
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('REDACTION','MINIMUM_REMOVAL')),
 source_revision BIGINT NOT NULL DEFAULT 0 CHECK(source_revision>=0),
 invalidated BOOLEAN NOT NULL DEFAULT FALSE,
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE commerce_retention_change_outbox (
 order_id BIGINT NOT NULL REFERENCES commerce_retention_source_state(order_id),
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 generation UUID NOT NULL,
 order_version BIGINT NOT NULL CHECK(order_version>=0),
 phase VARCHAR(20) NOT NULL CHECK(phase IN ('REDACTION','MINIMUM_REMOVAL')),
 source_revision BIGINT NOT NULL CHECK(source_revision>0),
 state VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','SENT','FAILED')),
 attempts INT NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 run_token UUID,
 run_until TIMESTAMPTZ,
 retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 sent_at TIMESTAMPTZ,
 code VARCHAR(24) CHECK(code IS NULL OR code IN ('TRANSPORT_FAILED','RETRY_LIMIT')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(order_id,binding_epoch),
 CHECK((run_token IS NULL)=(run_until IS NULL)),
 CHECK((state='LEASED')=(run_token IS NOT NULL)),
 CHECK((state='SENT')=(sent_at IS NOT NULL))
);
CREATE INDEX idx_retention_source_due ON commerce_retention_change_outbox(retry_at,order_id) WHERE state IN ('PENDING','LEASED','FAILED');

CREATE FUNCTION queue_commerce_retention_change(subject BIGINT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE binding commerce_retention_source_state%ROWTYPE;
BEGIN
 IF subject IS NULL THEN RETURN; END IF;
 PERFORM pg_advisory_xact_lock(436,hashint8(subject));
 UPDATE commerce_retention_source_state SET source_revision=source_revision+1,invalidated=TRUE,updated_at=CURRENT_TIMESTAMP
 WHERE order_id=subject RETURNING * INTO binding;
 IF NOT FOUND THEN RETURN; END IF;
 INSERT INTO commerce_retention_change_outbox(order_id,binding_epoch,generation,order_version,phase,source_revision)
 VALUES(binding.order_id,binding.binding_epoch,binding.generation,binding.order_version,binding.phase,binding.source_revision)
 ON CONFLICT(order_id,binding_epoch) DO UPDATE SET source_revision=EXCLUDED.source_revision,state='PENDING',attempts=0,
 run_token=NULL,run_until=NULL,retry_at=CURRENT_TIMESTAMP,code=NULL,updated_at=CURRENT_TIMESTAMP
 WHERE commerce_retention_change_outbox.state<>'SENT';
END;
$$;
CREATE FUNCTION capture_commerce_retention_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_row JSONB; new_row JSONB; item JSONB; parent_order BIGINT; linked BIGINT[];
 subjects BIGINT[]:=ARRAY[]::bigint[]; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
 IF TG_OP<>'INSERT' THEN old_row:=to_jsonb(OLD); END IF;
 IF TG_OP<>'DELETE' THEN new_row:=to_jsonb(NEW); END IF;
 FOREACH item IN ARRAY ARRAY[old_row,new_row] LOOP
  IF item IS NULL THEN CONTINUE; END IF;
  IF TG_TABLE_NAME IN ('payment_refunds','stripe_webhook_receipts') THEN
   SELECT order_id INTO parent_order FROM payments WHERE id=(item->>'payment_id')::bigint;
   subjects:=array_append(subjects,parent_order);
   IF TG_TABLE_NAME='payment_refunds' THEN subjects:=array_append(subjects,(item->>'order_id')::bigint); END IF;
  ELSE subjects:=array_append(subjects,(item->>'order_id')::bigint); END IF;
 END LOOP;
 -- Both declared and canonical parents are negative invalidations only.
 -- Deduplicate and lock every affected purchase in a stable order.
 FOR subject IN SELECT DISTINCT value FROM unnest(subjects) value WHERE value>0 ORDER BY value LOOP
  PERFORM queue_commerce_retention_change(subject);
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_source_change BEFORE INSERT OR UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION capture_commerce_retention_change();
CREATE TRIGGER retention_source_change BEFORE INSERT OR UPDATE OR DELETE ON payment_refunds FOR EACH ROW EXECUTE FUNCTION capture_commerce_retention_change();
CREATE TRIGGER retention_source_change BEFORE INSERT OR UPDATE OR DELETE ON payment_follow_ups FOR EACH ROW EXECUTE FUNCTION capture_commerce_retention_change();
CREATE TRIGGER retention_source_change BEFORE INSERT OR UPDATE OR DELETE ON payment_order_gates FOR EACH ROW EXECUTE FUNCTION capture_commerce_retention_change();
CREATE TRIGGER retention_source_change BEFORE INSERT OR UPDATE OR DELETE ON payment_outbox_events FOR EACH ROW EXECUTE FUNCTION capture_commerce_retention_change();
CREATE TRIGGER retention_source_change BEFORE INSERT OR UPDATE OR DELETE ON stripe_webhook_receipts FOR EACH ROW EXECUTE FUNCTION capture_commerce_retention_change();

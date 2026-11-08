-- Source observation leases only. Does not authorize redaction or enable retention.
ALTER TABLE commerce_retention_source_state ADD COLUMN retention_fence_token UUID;
ALTER TABLE commerce_retention_source_state ADD COLUMN retention_fence_until TIMESTAMPTZ;
ALTER TABLE commerce_retention_source_state ADD CONSTRAINT retention_fence_pair
 CHECK ((retention_fence_token IS NULL) = (retention_fence_until IS NULL));

CREATE FUNCTION assert_commerce_retention_unfenced(subject BIGINT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF subject IS NULL THEN RETURN; END IF;
 PERFORM pg_advisory_xact_lock(436,hashint8(subject));
 IF EXISTS(SELECT 1 FROM commerce_retention_source_state WHERE order_id=subject
    AND retention_fence_token IS NOT NULL AND retention_fence_until>clock_timestamp()) THEN
  RAISE EXCEPTION 'Retention source write fence is active' USING ERRCODE='55000';
 END IF;
END;
$$;
CREATE FUNCTION protect_retention_fence_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.restore_blocked THEN
  NEW.retention_fence_token:=NULL; NEW.retention_fence_until:=NULL; RETURN NEW;
 END IF;
 IF OLD.retention_fence_token IS NOT NULL AND OLD.retention_fence_until>clock_timestamp()
  AND (NEW.generation,NEW.order_version,NEW.phase,NEW.binding_epoch)
   IS DISTINCT FROM (OLD.generation,OLD.order_version,OLD.phase,OLD.binding_epoch) THEN
  RAISE EXCEPTION 'Retention binding is fenced' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_fence_binding BEFORE UPDATE ON commerce_retention_source_state
 FOR EACH ROW EXECUTE FUNCTION protect_retention_fence_binding();

CREATE FUNCTION enforce_retention_write_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_row JSONB; new_row JSONB; item JSONB; parent_order BIGINT; linked BIGINT[];
 subjects BIGINT[]:=ARRAY[]::bigint[]; subject BIGINT;
BEGIN
 IF TG_OP<>'INSERT' THEN old_row:=to_jsonb(OLD); END IF;
 IF TG_OP<>'DELETE' THEN new_row:=to_jsonb(NEW); END IF;
 FOREACH item IN ARRAY ARRAY[old_row,new_row] LOOP
  IF item IS NULL THEN CONTINUE; END IF;
  IF TG_TABLE_NAME='event_identity_aliases' THEN
   SELECT array_agg(order_id) INTO linked FROM event_inbox WHERE event_id IN (item->>'alias_id',item->>'canonical_id');
   subjects:=subjects||coalesce(linked,ARRAY[]::bigint[]);
  ELSIF TG_TABLE_NAME='event_inbox' THEN subjects:=array_append(subjects,(item->>'order_id')::bigint);
  ELSE
   IF item->>'event_type' IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED') THEN
    subjects:=array_append(subjects,(item->>'source_id')::bigint);
   END IF;
   IF TG_TABLE_NAME='notification_dead_letters' THEN
    SELECT source_id INTO parent_order FROM notifications WHERE id=(item->>'notification_id')::bigint
     AND event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED');
    subjects:=array_append(subjects,parent_order);
   END IF;
  END IF;
 END LOOP;
 -- Both declared and canonical parents are negative invalidations only.
 -- Deduplicate and lock every affected purchase in a stable order.
 FOR subject IN SELECT DISTINCT value FROM unnest(subjects) value WHERE value>0 ORDER BY value LOOP
  PERFORM assert_commerce_retention_unfenced(subject);
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER a_retention_write_fence BEFORE INSERT OR UPDATE OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION enforce_retention_write_fence();
CREATE TRIGGER a_retention_write_fence BEFORE INSERT OR UPDATE OR DELETE ON notification_dead_letters FOR EACH ROW EXECUTE FUNCTION enforce_retention_write_fence();
CREATE TRIGGER a_retention_write_fence BEFORE INSERT OR UPDATE OR DELETE ON event_inbox FOR EACH ROW EXECUTE FUNCTION enforce_retention_write_fence();
CREATE TRIGGER a_retention_write_fence BEFORE INSERT OR UPDATE OR DELETE ON event_identity_aliases FOR EACH ROW EXECUTE FUNCTION enforce_retention_write_fence();

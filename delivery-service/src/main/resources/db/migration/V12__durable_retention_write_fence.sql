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
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 IF previous IS NOT NULL AND subject IS NOT NULL AND previous<>subject THEN
  PERFORM assert_commerce_retention_unfenced(least(previous,subject));
  PERFORM assert_commerce_retention_unfenced(greatest(previous,subject));
 ELSE PERFORM assert_commerce_retention_unfenced(coalesce(previous,subject)); END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER a_retention_write_fence BEFORE INSERT OR UPDATE OR DELETE ON shipments FOR EACH ROW EXECUTE FUNCTION enforce_retention_write_fence();

-- Ordinary content changes must invalidate observations; no untrusted redaction bypass.
CREATE OR REPLACE FUNCTION capture_commerce_retention_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
 IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 IF previous IS NOT NULL AND subject IS NOT NULL AND previous<>subject THEN
  PERFORM queue_commerce_retention_change(least(previous,subject));
  PERFORM queue_commerce_retention_change(greatest(previous,subject));
 ELSE PERFORM queue_commerce_retention_change(coalesce(previous,subject)); END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;

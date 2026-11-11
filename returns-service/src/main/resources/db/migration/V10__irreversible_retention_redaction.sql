-- Do not rely on a separately inserted account-deletion lock.
CREATE FUNCTION protect_redacted_return_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent BIGINT; purchase BIGINT; redacted BOOLEAN;
BEGIN
 IF TG_TABLE_NAME='return_requests' THEN
  PERFORM pg_advisory_xact_lock_shared(435,hashint8(OLD.order_id));
  PERFORM pg_advisory_xact_lock(436,hashint8(OLD.order_id));
  IF OLD.privacy_redacted AND (TG_OP='DELETE' OR NOT NEW.privacy_redacted) THEN
   RAISE EXCEPTION 'Redaction cannot be reverted' USING ERRCODE='23514';
  END IF;
 ELSE
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  parent:=NEW.return_id;
  SELECT order_id INTO purchase FROM return_requests WHERE id=parent;
  IF purchase IS NOT NULL THEN
   PERFORM pg_advisory_xact_lock_shared(435,hashint8(purchase));
   PERFORM pg_advisory_xact_lock(436,hashint8(purchase));
  END IF;
  SELECT privacy_redacted INTO redacted FROM return_requests WHERE id=parent FOR KEY SHARE;
  IF redacted AND ((TG_TABLE_NAME='return_lines' AND to_jsonb(NEW)->>'product_name' IS NOT NULL)
    OR (TG_TABLE_NAME='return_events' AND (to_jsonb(NEW)->>'note' IS NOT NULL OR to_jsonb(NEW)->>'actor_ref' IS NOT NULL))) THEN
   RAISE EXCEPTION 'Redacted content cannot be restored' USING ERRCODE='23514';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER a_irreversible_redaction BEFORE UPDATE OR DELETE ON return_requests FOR EACH ROW EXECUTE FUNCTION protect_redacted_return_state();
CREATE TRIGGER a_irreversible_redaction BEFORE INSERT OR UPDATE ON return_lines FOR EACH ROW EXECUTE FUNCTION protect_redacted_return_state();
CREATE TRIGGER a_irreversible_redaction BEFORE INSERT OR UPDATE ON return_events FOR EACH ROW EXECUTE FUNCTION protect_redacted_return_state();

-- Ordinary content changes must invalidate observations; no untrusted redaction bypass.
CREATE OR REPLACE FUNCTION capture_return_retention_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='return_requests' THEN
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM return_requests WHERE id=OLD.return_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM return_requests WHERE id=NEW.return_id; END IF;
 END IF;
 -- Deterministic lock order when a row moves between two purchases.
 IF previous IS NOT NULL AND subject IS NOT NULL AND previous<>subject THEN
  PERFORM queue_commerce_retention_change(least(previous,subject));
  PERFORM queue_commerce_retention_change(greatest(previous,subject));
 ELSE PERFORM queue_commerce_retention_change(coalesce(previous,subject)); END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;

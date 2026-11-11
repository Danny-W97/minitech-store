-- D02 commercial retention redaction: sync_error redaction guard update.
CREATE OR REPLACE FUNCTION capture_commerce_retention_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
  IF (to_jsonb(NEW)-ARRAY['updated_at','sync_error']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','sync_error']) THEN RETURN NEW; END IF;
 END IF;
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

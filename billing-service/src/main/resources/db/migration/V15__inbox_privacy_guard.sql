ALTER TABLE event_inbox ADD COLUMN order_id BIGINT;
CREATE INDEX idx_inbox_privacy_order ON event_inbox(order_id,event_id);
CREATE FUNCTION inbox_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT;previous BIGINT;data JSONB;
BEGIN
 IF TG_OP<>'DELETE' THEN
  subject:=NEW.order_id;
  IF subject IS NULL AND NEW.status='ACCEPTED' THEN
   subject:=NEW.related_id;
  END IF;
  IF subject IS NULL AND NEW.payload IS NOT NULL THEN
   data:=NEW.payload::jsonb;IF data->>'orderId' ~ '^[1-9][0-9]{0,18}$' THEN IF (data->>'orderId')::numeric<=9223372036854775807 THEN subject:=(data->>'orderId')::bigint; END IF; END IF;
  END IF;NEW.order_id:=subject;
 END IF;
 IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 IF previous IS NOT NULL THEN PERFORM pg_advisory_xact_lock_shared(435,hashint8(previous)); END IF;
 IF previous IS NOT NULL AND previous IS DISTINCT FROM subject THEN RAISE EXCEPTION 'Inbox privacy association is read only' USING ERRCODE='23514'; END IF;
 subject:=coalesce(subject,previous);
 IF subject IS NOT NULL THEN PERFORM pg_advisory_xact_lock_shared(435,hashint8(subject)); END IF;
 IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=subject) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Inbox privacy identity retained' USING ERRCODE='23514'; END IF;
  IF NEW.payload IS NOT NULL OR NEW.error IS NOT NULL THEN RAISE EXCEPTION 'Private inbox fields after privacy fence' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND (to_jsonb(NEW)-ARRAY['payload','error','diagnostics_compacted_at','order_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['payload','error','diagnostics_compacted_at','order_id']) THEN RAISE EXCEPTION 'Inbox privacy identity retained' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END;$$;
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON event_inbox FOR EACH ROW EXECUTE FUNCTION inbox_privacy_write_guard();

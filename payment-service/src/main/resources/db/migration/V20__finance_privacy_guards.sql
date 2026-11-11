CREATE TABLE commerce_privacy_locks (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),customer_id BIGINT NOT NULL CHECK(customer_id>0),request_id UUID NOT NULL
);
CREATE INDEX idx_commerce_privacy_customer ON commerce_privacy_locks(customer_id,order_id);

CREATE FUNCTION finance_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT;previous BIGINT;
BEGIN
 IF TG_TABLE_NAME='payment_refunds' THEN
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM payments WHERE id=NEW.payment_id; END IF;
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM payments WHERE id=OLD.payment_id; END IF;
 ELSE
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 END IF;
 IF previous IS NOT NULL AND previous IS DISTINCT FROM subject THEN
  PERFORM pg_advisory_xact_lock_shared(435,hashint8(previous));
  IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=previous) THEN RAISE EXCEPTION 'Finance privacy record is read only' USING ERRCODE='23514'; END IF;
 END IF;
 subject:=coalesce(subject,previous);PERFORM pg_advisory_xact_lock_shared(435,hashint8(subject));
 IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=subject) THEN
  IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Finance privacy record is read only' USING ERRCODE='23514'; END IF;
  IF (to_jsonb(NEW)-ARRAY['updated_at','version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','version']) THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='payments' THEN
   IF NEW.failure_reason IS NULL AND (to_jsonb(NEW)-ARRAY['failure_reason','updated_at']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['failure_reason','updated_at']) THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='payment_follow_ups' THEN
   IF NEW.payload IS NULL AND NEW.last_error_code IS NULL AND (to_jsonb(NEW)-ARRAY['payload','last_error_code','updated_at']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['payload','last_error_code','updated_at']) THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='payment_outbox_events' THEN
   IF NEW.recipient IS NULL AND NEW.delivery_address IS NULL AND NEW.last_error IS NULL AND NEW.payload_compacted_at IS NOT NULL
    AND (NEW.event_key=OLD.event_key OR NEW.event_key='retained:'||encode(sha256(convert_to(OLD.event_key,'UTF8')),'hex'))
    AND NEW.stored_event_id=coalesce(OLD.stored_event_id,'payment-'||encode(sha256(convert_to(OLD.event_key,'UTF8')),'hex'))
    AND (to_jsonb(NEW)-ARRAY['recipient','delivery_address','last_error','payload_compacted_at','event_key','stored_event_id','updated_at']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['recipient','delivery_address','last_error','payload_compacted_at','event_key','stored_event_id','updated_at']) THEN RETURN NEW; END IF;
  END IF;
  RAISE EXCEPTION 'Finance privacy record is read only' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END;$$;
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON payments FOR EACH ROW EXECUTE FUNCTION finance_privacy_write_guard();
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON payment_refunds FOR EACH ROW EXECUTE FUNCTION finance_privacy_write_guard();
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON payment_follow_ups FOR EACH ROW EXECUTE FUNCTION finance_privacy_write_guard();
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON payment_order_gates FOR EACH ROW EXECUTE FUNCTION finance_privacy_write_guard();
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON payment_outbox_events FOR EACH ROW EXECUTE FUNCTION finance_privacy_write_guard();

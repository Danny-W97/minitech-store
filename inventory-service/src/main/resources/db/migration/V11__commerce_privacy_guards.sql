-- A43.35: stable order-scoped receipt, no email/text or automatic TTL.
CREATE TABLE commerce_privacy_locks (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),
 customer_id BIGINT NOT NULL CHECK(customer_id>0),
 request_id UUID NOT NULL
);
CREATE INDEX idx_commerce_privacy_customer ON commerce_privacy_locks(customer_id,order_id);
CREATE FUNCTION commerce_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT; previous BIGINT;
BEGIN
 IF TG_TABLE_NAME='inventory_reservations' THEN
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF; IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 END IF;
 IF TG_TABLE_NAME='inventory_reservation_items' THEN
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM inventory_reservations WHERE id=NEW.reservation_id; END IF;
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM inventory_reservations WHERE id=OLD.reservation_id; END IF;
 END IF;
 IF previous IS NOT NULL AND previous IS DISTINCT FROM subject THEN
  PERFORM pg_advisory_xact_lock_shared(435,hashint8(previous));
  IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=previous) THEN RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514'; END IF;
 END IF;
 subject:=coalesce(subject,previous);
 IF subject IS NOT NULL THEN PERFORM pg_advisory_xact_lock_shared(435,hashint8(subject)); END IF;
 IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=subject) THEN
  IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514'; END IF;
  IF (to_jsonb(NEW)-ARRAY['updated_at','version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','version']) THEN RETURN NEW; END IF;
  RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON inventory_reservations FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON inventory_reservation_items FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();

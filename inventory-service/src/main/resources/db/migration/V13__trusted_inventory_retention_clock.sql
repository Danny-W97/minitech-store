-- Source-local lifecycle clock. No historical timestamps or expiry are inferred.
ALTER TABLE commerce_retention_source_state ADD COLUMN local_open_seen BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE commerce_retention_source_state ADD COLUMN local_reconciled_at TIMESTAMPTZ;
ALTER TABLE commerce_retention_source_state ADD CONSTRAINT inventory_retention_open_clock CHECK(NOT local_open_seen OR local_reconciled_at IS NULL);

CREATE FUNCTION inventory_retention_ready(subject BIGINT) RETURNS boolean LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM inventory_reservations r WHERE r.order_id=subject
  AND r.status IN ('CONFIRMED','RELEASED')
  AND (SELECT count(*) FROM inventory_reservation_items i WHERE i.reservation_id=r.id) BETWEEN 1 AND 100
  AND NOT EXISTS(SELECT 1 FROM inventory_reservation_items i WHERE i.reservation_id=r.id AND (i.product_id<=0 OR i.quantity<=0))
  AND NOT EXISTS(SELECT 1 FROM inventory_reservation_items i WHERE i.reservation_id=r.id GROUP BY i.product_id HAVING count(*)>1));
$$;

CREATE FUNCTION protect_inventory_retention_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retention source history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.local_open_seen OR NEW.local_reconciled_at IS NOT NULL THEN RAISE EXCEPTION 'No supplied inventory clock' USING ERRCODE='23514'; END IF;
 ELSIF NEW.order_id<>OLD.order_id OR (OLD.restore_blocked AND NOT NEW.restore_blocked)
  OR (pg_trigger_depth()<2 AND (NEW.local_open_seen,NEW.local_reconciled_at) IS DISTINCT FROM (OLD.local_open_seen,OLD.local_reconciled_at)) THEN
  RAISE EXCEPTION 'Inventory retention clock is source controlled' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_retention_clock_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_source_state
 FOR EACH ROW EXECUTE FUNCTION protect_inventory_retention_clock();

CREATE FUNCTION initialize_inventory_retention_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE commerce_retention_source_state SET local_open_seen=NOT inventory_retention_ready(NEW.order_id) WHERE order_id=NEW.order_id;
 RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_retention_clock_init AFTER INSERT ON commerce_retention_source_state
 FOR EACH ROW EXECUTE FUNCTION initialize_inventory_retention_clock();

CREATE FUNCTION track_inventory_retention_clock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT; target BIGINT; ready BOOLEAN;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME='inventory_reservation_items' THEN
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM inventory_reservations WHERE id=OLD.reservation_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM inventory_reservations WHERE id=NEW.reservation_id; END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 END IF;
 -- The existing BEFORE trigger already serializes old/new scopes using lock 436.
 FOR target IN SELECT order_id FROM commerce_retention_source_state WHERE order_id IN (previous,subject) ORDER BY order_id LOOP
  ready:=inventory_retention_ready(target);
  UPDATE commerce_retention_source_state SET
   local_reconciled_at=CASE WHEN ready AND local_open_seen AND NOT restore_blocked THEN clock_timestamp() ELSE NULL END,
   local_open_seen=NOT ready WHERE order_id=target;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER inventory_retention_clock AFTER INSERT OR UPDATE OR DELETE ON inventory_reservations FOR EACH ROW EXECUTE FUNCTION track_inventory_retention_clock();
CREATE TRIGGER inventory_retention_item_clock AFTER INSERT OR UPDATE OR DELETE ON inventory_reservation_items FOR EACH ROW EXECUTE FUNCTION track_inventory_retention_clock();

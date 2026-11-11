-- Local Order milestone, never a global reconciliation or preservation clearance.
-- No backfill from created_at/updated_at and no automatic tracking of all orders.
CREATE TABLE commerce_retention_order_state (
 order_id BIGINT PRIMARY KEY REFERENCES orders(id),
 process_revision BIGINT NOT NULL DEFAULT 0 CHECK(process_revision>=0),
 open_seen BOOLEAN NOT NULL DEFAULT FALSE,
 reconciled_at TIMESTAMPTZ,
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(NOT open_seen OR reconciled_at IS NULL)
);
CREATE FUNCTION commerce_retention_order_ready(subject BIGINT) RETURNS boolean LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(
  SELECT 1 FROM orders o JOIN order_sagas s ON s.order_id=o.id
  WHERE o.id=subject AND NOT o.lifecycle_review_required AND o.checkout_request_hash IS NOT NULL
   AND s.processing_token IS NULL AND s.processing_until IS NULL AND s.next_attempt_at IS NULL
   AND ((o.status='CANCELLED' AND s.state='FAILED') OR
        (o.status='COMPLETED' AND s.state='COMPLETED' AND o.inventory_reservation_id>0
         AND o.delivery_tracking_number IS NOT NULL AND o.delivery_status IN ('DELIVERED','PICKED_UP') AND o.delivery_revision>=0
         AND (o.deferred_payment_method IS NULL OR o.collection_reference IS NOT NULL)))
   AND ((o.refund_state IS NULL AND o.refund_payment_id IS NULL) OR (o.refund_state='REFUNDED' AND o.refund_payment_id>0))
   AND (o.promotion_code IS NULL OR o.coupon_reservation_verified)
   AND EXISTS(SELECT 1 FROM order_items i WHERE i.order_id=o.id)
   AND NOT EXISTS(SELECT 1 FROM order_items i WHERE i.order_id=o.id AND (i.product_id<=0 OR i.quantity<=0 OR i.unit_price<0 OR i.total_price<>i.unit_price*i.quantity))
   AND o.total_price=(SELECT sum(i.total_price) FROM order_items i WHERE i.order_id=o.id)+o.delivery_cost-o.discount_amount
 );
$$;
CREATE FUNCTION protect_commerce_retention_order_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retention order history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.reconciled_at IS NOT NULL OR NEW.process_revision<>0 THEN RAISE EXCEPTION 'No supplied reconciliation clock' USING ERRCODE='23514'; END IF;
 ELSIF NEW.order_id<>OLD.order_id OR NEW.created_at<>OLD.created_at OR (OLD.restore_blocked AND NOT NEW.restore_blocked)
  OR (pg_trigger_depth()<2 AND (NEW.process_revision,NEW.open_seen,NEW.reconciled_at) IS DISTINCT FROM (OLD.process_revision,OLD.open_seen,OLD.reconciled_at)) THEN
  RAISE EXCEPTION 'Retention order clock is source controlled' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_order_state_protocol BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_order_state
 FOR EACH ROW EXECUTE FUNCTION protect_commerce_retention_order_state();

CREATE FUNCTION track_commerce_retention_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT; target BIGINT; ready BOOLEAN;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF TG_TABLE_NAME='orders' THEN
   IF (to_jsonb(NEW)-ARRAY['updated_at','version','privacy_redacted','customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','delivery_date','delivery_slot','pickup_location'])
     IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','version','privacy_redacted','customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','delivery_date','delivery_slot','pickup_location']) THEN RETURN NEW; END IF;
  ELSIF TG_TABLE_NAME='order_items' THEN
   IF (to_jsonb(NEW)-'product_name') IS NOT DISTINCT FROM (to_jsonb(OLD)-'product_name') THEN RETURN NEW; END IF;
  ELSE
   IF (to_jsonb(NEW)-ARRAY['updated_at','version','last_error']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','version','last_error']) THEN RETURN NEW; END IF;
  END IF;
 END IF;
 IF TG_TABLE_NAME='orders' THEN
  IF TG_OP<>'INSERT' THEN previous:=OLD.id; END IF; IF TG_OP<>'DELETE' THEN subject:=NEW.id; END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF; IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 END IF;
 FOR target IN SELECT order_id FROM commerce_retention_order_state WHERE order_id IN (previous,subject) ORDER BY order_id LOOP
  PERFORM 1 FROM orders WHERE id=target FOR NO KEY UPDATE;
  ready:=commerce_retention_order_ready(target);
  UPDATE commerce_retention_order_state SET process_revision=process_revision+1,
    reconciled_at=CASE WHEN ready AND open_seen AND NOT restore_blocked THEN clock_timestamp() ELSE NULL END,
    open_seen=NOT ready,updated_at=CURRENT_TIMESTAMP WHERE order_id=target;
 END LOOP;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER retention_order_clock AFTER UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION track_commerce_retention_order();
CREATE TRIGGER retention_item_clock AFTER INSERT OR UPDATE OR DELETE ON order_items FOR EACH ROW EXECUTE FUNCTION track_commerce_retention_order();
CREATE TRIGGER retention_saga_clock AFTER INSERT OR UPDATE OR DELETE ON order_sagas FOR EACH ROW EXECUTE FUNCTION track_commerce_retention_order();

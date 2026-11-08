-- D02 commercial retention redaction: product_name nullable, delivery schedule redaction, and privacy guard updates.
ALTER TABLE order_items ALTER COLUMN product_name DROP NOT NULL;
ALTER TABLE orders ADD CONSTRAINT ck_privacy_delivery_schedule_removed
 CHECK(NOT privacy_redacted OR (delivery_date IS NULL AND delivery_slot IS NULL AND pickup_location IS NULL));

CREATE OR REPLACE FUNCTION commerce_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT; previous BIGINT;
BEGIN
 IF TG_TABLE_NAME='orders' THEN
  IF TG_OP<>'DELETE' THEN subject:=NEW.id; END IF; IF TG_OP<>'INSERT' THEN previous:=OLD.id; END IF;
 END IF;
 IF TG_TABLE_NAME='order_items' THEN
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF; IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 END IF;
 IF TG_TABLE_NAME='order_sagas' THEN
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF; IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
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
  IF TG_TABLE_NAME='orders' THEN
   IF NOT OLD.privacy_redacted AND NEW.privacy_redacted
    AND NEW.customer_email IS NULL AND NEW.delivery_first_name IS NULL AND NEW.delivery_last_name IS NULL
    AND NEW.delivery_street IS NULL AND NEW.delivery_postal_code IS NULL AND NEW.delivery_city IS NULL
    AND NEW.delivery_country IS NULL AND NEW.delivery_phone IS NULL AND NEW.delivery_date IS NULL
    AND NEW.delivery_slot IS NULL AND NEW.pickup_location IS NULL
    AND (to_jsonb(NEW)-ARRAY['customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','delivery_date','delivery_slot','pickup_location','privacy_redacted','updated_at','version'])
    IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','delivery_date','delivery_slot','pickup_location','privacy_redacted','updated_at','version'])
   THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='order_items' THEN
   IF NEW.product_name IS NULL AND (to_jsonb(NEW)-'product_name') IS NOT DISTINCT FROM (to_jsonb(OLD)-'product_name') THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='order_sagas' THEN
   IF NEW.last_error IS NULL AND (to_jsonb(NEW)-ARRAY['last_error','updated_at','version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['last_error','updated_at','version']) THEN RETURN NEW; END IF;
  END IF;
  RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION invalidate_commerce_retention() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='orders' THEN
   IF (to_jsonb(NEW)-ARRAY['updated_at','version','privacy_redacted','customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','delivery_date','delivery_slot','pickup_location'])
     IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','version','privacy_redacted','customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','delivery_date','delivery_slot','pickup_location']) THEN RETURN NEW; END IF;
  ELSIF TG_TABLE_NAME='order_items' THEN
   IF (to_jsonb(NEW)-'product_name') IS NOT DISTINCT FROM (to_jsonb(OLD)-'product_name') THEN RETURN NEW; END IF;
  END IF;
 END IF;
 IF TG_TABLE_NAME='orders' THEN
  IF TG_OP<>'INSERT' THEN previous:=OLD.id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.id; END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 END IF;
 UPDATE commerce_retention_jobs SET current=FALSE,state='STALE',code='ORDER_CHANGED',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
 WHERE current AND order_id IN (previous,subject);
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;

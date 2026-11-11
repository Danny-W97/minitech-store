-- A43.35: stable order-scoped receipt, no email/text or automatic TTL.
CREATE TABLE commerce_privacy_locks (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),
 customer_id BIGINT NOT NULL CHECK(customer_id>0),
 request_id UUID NOT NULL
);
CREATE INDEX idx_commerce_privacy_customer ON commerce_privacy_locks(customer_id,order_id);
ALTER TABLE orders ADD COLUMN privacy_redacted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ALTER COLUMN customer_email DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_first_name DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_last_name DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_street DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_postal_code DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_city DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_country DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_phone DROP NOT NULL;
ALTER TABLE orders ADD CONSTRAINT ck_privacy_contact_removed CHECK(NOT privacy_redacted OR (customer_email IS NULL AND delivery_first_name IS NULL AND delivery_last_name IS NULL AND delivery_street IS NULL AND delivery_postal_code IS NULL AND delivery_city IS NULL AND delivery_country IS NULL AND delivery_phone IS NULL));
-- Preserve the original required-field invariant for every ordinary order.
ALTER TABLE orders ADD CONSTRAINT ck_privacy_contact_required CHECK(privacy_redacted OR (customer_email IS NOT NULL AND delivery_first_name IS NOT NULL AND delivery_last_name IS NOT NULL AND delivery_street IS NOT NULL AND delivery_postal_code IS NOT NULL AND delivery_city IS NOT NULL AND delivery_country IS NOT NULL));
CREATE FUNCTION commerce_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
  IF TG_TABLE_NAME='orders' THEN IF NOT OLD.privacy_redacted AND NEW.privacy_redacted AND NEW.customer_email IS NULL AND NEW.delivery_first_name IS NULL AND NEW.delivery_last_name IS NULL AND NEW.delivery_street IS NULL AND NEW.delivery_postal_code IS NULL AND NEW.delivery_city IS NULL AND NEW.delivery_country IS NULL AND NEW.delivery_phone IS NULL
   AND (to_jsonb(NEW)-ARRAY['customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','privacy_redacted','updated_at','version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['customer_email','delivery_first_name','delivery_last_name','delivery_street','delivery_postal_code','delivery_city','delivery_country','delivery_phone','privacy_redacted','updated_at','version']) THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='order_sagas' THEN IF NEW.last_error IS NULL AND (to_jsonb(NEW)-ARRAY['last_error','updated_at','version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['last_error','updated_at','version']) THEN RETURN NEW; END IF; END IF;
  RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON orders FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON order_items FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON order_sagas FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();

-- A43.35: stable order-scoped receipt, no email/text or automatic TTL.
CREATE TABLE commerce_privacy_locks (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),
 customer_id BIGINT NOT NULL CHECK(customer_id>0),
 request_id UUID NOT NULL
);
CREATE INDEX idx_commerce_privacy_customer ON commerce_privacy_locks(customer_id,order_id);
ALTER TABLE return_requests ADD COLUMN privacy_redacted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE return_requests ALTER COLUMN customer_email DROP NOT NULL;
ALTER TABLE return_requests ALTER COLUMN reason DROP NOT NULL;
ALTER TABLE return_requests ALTER COLUMN description DROP NOT NULL;
ALTER TABLE return_requests ALTER COLUMN moderation_note DROP NOT NULL;
ALTER TABLE return_requests ADD CONSTRAINT ck_privacy_contact_removed CHECK(NOT privacy_redacted OR (customer_email IS NULL AND reason IS NULL AND description IS NULL AND moderation_note IS NULL));
-- NULL is allowed only for the explicit one-way privacy redaction.
ALTER TABLE return_requests ADD CONSTRAINT ck_privacy_contact_required CHECK(privacy_redacted OR (customer_email IS NOT NULL AND reason IS NOT NULL AND description IS NOT NULL));
ALTER TABLE return_events ALTER COLUMN actor_ref DROP NOT NULL;
CREATE FUNCTION commerce_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT; previous BIGINT;
BEGIN
 IF TG_TABLE_NAME='return_requests' THEN
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF; IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 END IF;
 IF TG_TABLE_NAME='return_lines' THEN
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM return_requests WHERE id=NEW.return_id; END IF;
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM return_requests WHERE id=OLD.return_id; END IF;
 END IF;
 IF TG_TABLE_NAME='return_events' THEN
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM return_requests WHERE id=NEW.return_id; END IF;
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM return_requests WHERE id=OLD.return_id; END IF;
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
  IF TG_TABLE_NAME='return_requests' THEN IF NOT OLD.privacy_redacted AND NEW.privacy_redacted AND NEW.customer_email IS NULL AND NEW.reason IS NULL AND NEW.description IS NULL AND NEW.moderation_note IS NULL
   AND (to_jsonb(NEW)-ARRAY['customer_email','reason','description','moderation_note','privacy_redacted','updated_at','version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['customer_email','reason','description','moderation_note','privacy_redacted','updated_at','version']) THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='return_events' THEN IF NEW.note IS NULL AND NEW.actor_ref IS NULL AND (to_jsonb(NEW)-ARRAY['note','actor_ref']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['note','actor_ref']) THEN RETURN NEW; END IF; END IF;
  RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514';
 END IF;
 -- Preserve the former NOT NULL invariant outside the sealed cleanup scope.
 IF TG_TABLE_NAME='return_events' AND TG_OP<>'DELETE' THEN
  IF NEW.actor_ref IS NULL THEN RAISE EXCEPTION 'Ordinary return event requires actor reference' USING ERRCODE='23502'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END;
$$;
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON return_requests FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON return_lines FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();
CREATE TRIGGER commerce_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON return_events FOR EACH ROW EXECUTE FUNCTION commerce_privacy_write_guard();

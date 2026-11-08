-- Fixed UTF-8 encoding is part of the identity protocol, independent of client settings.
CREATE FUNCTION privacy_identity_hash(value TEXT) RETURNS TEXT LANGUAGE SQL IMMUTABLE STRICT AS $$
 SELECT encode(sha256(convert_to(value,'UTF8')),'hex')
$$;
CREATE TABLE commerce_privacy_locks (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),customer_id BIGINT NOT NULL CHECK(customer_id>0),request_id UUID NOT NULL
);
CREATE INDEX idx_commerce_privacy_customer ON commerce_privacy_locks(customer_id,order_id);

CREATE TABLE account_privacy_copy_locks(customer_id BIGINT PRIMARY KEY CHECK(customer_id>0),request_id UUID NOT NULL);
ALTER TABLE notifications ADD COLUMN subject_customer_id BIGINT CHECK(subject_customer_id>0);
CREATE INDEX idx_notification_account_subject ON notifications(subject_customer_id,event_type,id);
ALTER TABLE notifications ADD COLUMN privacy_redacted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notifications ADD COLUMN recipient_hash VARCHAR(64);
ALTER TABLE notifications ALTER COLUMN recipient DROP NOT NULL;
ALTER TABLE notification_dead_letters ALTER COLUMN recipient DROP NOT NULL;
ALTER TABLE notification_dead_letters ADD CONSTRAINT ck_dead_letter_privacy_recipient CHECK(recipient IS NOT NULL OR (content_compacted_at IS NOT NULL AND status='RESOLVED' AND resolved_at IS NOT NULL));
CREATE UNIQUE INDEX uk_notification_compatible_identity ON notifications(event_type,source_id,(coalesce(recipient_hash,privacy_identity_hash(recipient))));
ALTER TABLE notifications ADD CONSTRAINT ck_notification_privacy_content CHECK(NOT privacy_redacted OR (recipient IS NULL AND subject IS NULL AND message IS NULL AND failure_reason IS NULL AND recipient_hash IS NOT NULL AND content_compacted_at IS NOT NULL));
ALTER TABLE notifications ADD CONSTRAINT ck_notification_required_recipient CHECK(privacy_redacted OR recipient IS NOT NULL);
CREATE FUNCTION notification_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT;previous BIGINT;sealed BOOLEAN:=FALSE;oldsealed BOOLEAN:=FALSE;
BEGIN
 IF TG_OP<>'DELETE' THEN
  subject:=NEW.source_id;
  IF NEW.event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED') THEN
   PERFORM pg_advisory_xact_lock_shared(435,hashint8(subject));sealed:=EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=subject);
  ELSIF NEW.event_type IN ('CUSTOMER_REGISTERED','PASSWORD_RESET_REQUESTED') THEN
   IF TG_TABLE_NAME='notifications' THEN subject:=coalesce(NEW.subject_customer_id,CASE WHEN NEW.event_type='CUSTOMER_REGISTERED' THEN NEW.source_id ELSE NULL END);
   ELSE SELECT coalesce(n.subject_customer_id,CASE WHEN n.event_type='CUSTOMER_REGISTERED' THEN n.source_id ELSE NULL END) INTO subject FROM notifications n WHERE n.id=NEW.notification_id AND n.event_type=NEW.event_type AND n.source_id=NEW.source_id; END IF;
   IF subject IS NOT NULL THEN PERFORM pg_advisory_xact_lock_shared(-subject);sealed:=EXISTS(SELECT 1 FROM account_privacy_copy_locks WHERE customer_id=subject); END IF;
   IF TG_TABLE_NAME='notifications' AND TG_OP='INSERT' AND NEW.event_type='PASSWORD_RESET_REQUESTED' AND subject IS NULL THEN RAISE EXCEPTION 'Verified account subject required' USING ERRCODE='23514'; END IF;
   IF TG_TABLE_NAME='notifications' AND TG_OP='INSERT' AND EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=subject) THEN RAISE EXCEPTION 'Account privacy fence active' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 IF TG_OP<>'INSERT' THEN
  previous:=OLD.source_id;
  IF OLD.event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED') THEN PERFORM pg_advisory_xact_lock_shared(435,hashint8(previous));oldsealed:=EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=previous);
  ELSIF OLD.event_type IN ('CUSTOMER_REGISTERED','PASSWORD_RESET_REQUESTED') THEN
   IF TG_TABLE_NAME='notifications' THEN previous:=coalesce(OLD.subject_customer_id,CASE WHEN OLD.event_type='CUSTOMER_REGISTERED' THEN OLD.source_id ELSE NULL END);
   ELSE SELECT coalesce(n.subject_customer_id,CASE WHEN n.event_type='CUSTOMER_REGISTERED' THEN n.source_id ELSE NULL END) INTO previous FROM notifications n WHERE n.id=OLD.notification_id AND n.event_type=OLD.event_type AND n.source_id=OLD.source_id; END IF;
   IF previous IS NOT NULL THEN PERFORM pg_advisory_xact_lock_shared(-previous);oldsealed:=EXISTS(SELECT 1 FROM account_privacy_copy_locks WHERE customer_id=previous); END IF;
  END IF;
 END IF;
 IF sealed OR oldsealed THEN
  IF TG_OP<>'UPDATE' THEN RAISE EXCEPTION 'Notification privacy record is read only' USING ERRCODE='23514'; END IF;
  IF (to_jsonb(NEW)-ARRAY['updated_at','row_version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','row_version']) THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='notifications' THEN
   IF NEW.privacy_redacted AND NEW.recipient IS NULL AND NEW.subject IS NULL AND NEW.message IS NULL AND NEW.failure_reason IS NULL AND NEW.content_compacted_at IS NOT NULL
    AND NEW.recipient_hash=coalesce(OLD.recipient_hash,privacy_identity_hash(OLD.recipient))
    AND (to_jsonb(NEW)-ARRAY['recipient','recipient_hash','subject','message','failure_reason','content_compacted_at','privacy_redacted','updated_at']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['recipient','recipient_hash','subject','message','failure_reason','content_compacted_at','privacy_redacted','updated_at']) THEN RETURN NEW; END IF;
  ELSE
   IF NEW.recipient IS NULL AND NEW.subject IS NULL AND NEW.message IS NULL AND NEW.failure_reason IS NULL AND NEW.content_compacted_at IS NOT NULL
    AND (to_jsonb(NEW)-ARRAY['recipient','subject','message','failure_reason','content_compacted_at','updated_at','row_version']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['recipient','subject','message','failure_reason','content_compacted_at','updated_at','row_version']) THEN RETURN NEW; END IF;
  END IF;
  RAISE EXCEPTION 'Notification privacy record is read only' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END;$$;
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION notification_privacy_write_guard();
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON notification_dead_letters FOR EACH ROW EXECUTE FUNCTION notification_privacy_write_guard();

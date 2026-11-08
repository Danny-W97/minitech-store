-- D02 commercial retention redaction: return_lines product_name nullable and privacy guard updates.
ALTER TABLE return_lines ALTER COLUMN product_name DROP NOT NULL;

CREATE OR REPLACE FUNCTION commerce_privacy_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
  IF TG_TABLE_NAME='return_lines' THEN
   IF NEW.product_name IS NULL AND (to_jsonb(NEW)-'product_name') IS NOT DISTINCT FROM (to_jsonb(OLD)-'product_name') THEN RETURN NEW; END IF;
  END IF;
  IF TG_TABLE_NAME='return_events' THEN IF NEW.note IS NULL AND NEW.actor_ref IS NULL AND (to_jsonb(NEW)-ARRAY['note','actor_ref']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['note','actor_ref']) THEN RETURN NEW; END IF; END IF;
  RAISE EXCEPTION 'Commerce privacy record is read only' USING ERRCODE='23514';
 END IF;
 -- Preserve the former NOT NULL invariant outside the sealed cleanup scope.
 IF TG_TABLE_NAME='return_events' AND TG_OP<>'DELETE' THEN
  IF NEW.actor_ref IS NULL AND NOT EXISTS(SELECT 1 FROM return_requests WHERE id=NEW.return_id AND privacy_redacted) THEN RAISE EXCEPTION 'Ordinary return event requires actor reference' USING ERRCODE='23502'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION capture_return_retention_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
  IF TG_TABLE_NAME='return_requests' THEN
   IF (to_jsonb(NEW)-ARRAY['updated_at','version','privacy_redacted','customer_email','reason','description','moderation_note'])
     IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['updated_at','version','privacy_redacted','customer_email','reason','description','moderation_note']) THEN RETURN NEW; END IF;
  ELSIF TG_TABLE_NAME='return_lines' THEN
   IF (to_jsonb(NEW)-'product_name') IS NOT DISTINCT FROM (to_jsonb(OLD)-'product_name') THEN RETURN NEW; END IF;
  ELSIF TG_TABLE_NAME='return_events' THEN
   IF (to_jsonb(NEW)-ARRAY['note','actor_ref']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['note','actor_ref']) THEN RETURN NEW; END IF;
  END IF;
 END IF;
 IF TG_TABLE_NAME='return_requests' THEN
  IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
  IF TG_OP<>'DELETE' THEN subject:=NEW.order_id; END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN SELECT order_id INTO previous FROM return_requests WHERE id=OLD.return_id; END IF;
  IF TG_OP<>'DELETE' THEN SELECT order_id INTO subject FROM return_requests WHERE id=NEW.return_id; END IF;
 END IF;
 IF previous IS NOT NULL AND subject IS NOT NULL AND previous<>subject THEN
  PERFORM queue_commerce_retention_change(least(previous,subject));
  PERFORM queue_commerce_retention_change(greatest(previous,subject));
 ELSE PERFORM queue_commerce_retention_change(coalesce(previous,subject)); END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;

-- Ordinary contact/free-text changes must invalidate the observed generation.
-- V35's redaction exemptions also admitted unrelated business writes. No
-- mutation worker may borrow an assessment across those changes. No data erase.
CREATE OR REPLACE FUNCTION invalidate_commerce_retention() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous BIGINT; subject BIGINT;
BEGIN
 IF TG_OP='UPDATE' AND (to_jsonb(NEW)-'updated_at') IS NOT DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN RETURN NEW; END IF;
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

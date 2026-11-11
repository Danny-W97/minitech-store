CREATE TABLE commerce_privacy_locks (
 order_id BIGINT PRIMARY KEY CHECK(order_id>0),customer_id BIGINT NOT NULL CHECK(customer_id>0),request_id UUID NOT NULL
);
CREATE INDEX idx_commerce_privacy_customer ON commerce_privacy_locks(customer_id,order_id);

ALTER TABLE invoice_outbox_events ADD COLUMN order_id BIGINT;
ALTER TABLE invoice_outbox_events ADD COLUMN privacy_safe BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE invoice_outbox_events o SET order_id=i.order_id FROM invoices i WHERE o.event_id='invoice-issued-'||i.id;
UPDATE invoice_outbox_events o SET order_id=i.order_id FROM invoice_corrections c JOIN invoices i ON i.id=c.invoice_id WHERE o.event_id='invoice-corrected-'||c.id;
CREATE INDEX idx_invoice_outbox_privacy_order ON invoice_outbox_events(order_id,id);
CREATE FUNCTION billing_privacy_outbox_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT;previous BIGINT;data JSONB;
BEGIN
 IF TG_OP<>'DELETE' THEN subject:=NEW.order_id;
  IF subject IS NULL THEN
   SELECT i.order_id INTO subject FROM invoices i WHERE NEW.event_id='invoice-issued-'||i.id;
   IF subject IS NULL THEN SELECT i.order_id INTO subject FROM invoice_corrections c JOIN invoices i ON i.id=c.invoice_id WHERE NEW.event_id='invoice-corrected-'||c.id; END IF;
   NEW.order_id:=subject;
  END IF;
 END IF;
 IF TG_OP<>'INSERT' THEN previous:=OLD.order_id; END IF;
 IF previous IS NOT NULL AND previous IS DISTINCT FROM subject THEN
  PERFORM pg_advisory_xact_lock_shared(435,hashint8(previous));
  IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=previous) THEN RAISE EXCEPTION 'Invoice privacy association is read only' USING ERRCODE='23514'; END IF;
 END IF;
 subject:=coalesce(subject,previous);IF subject IS NOT NULL THEN PERFORM pg_advisory_xact_lock_shared(435,hashint8(subject)); END IF;
 IF EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=subject) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Invoice privacy record is read only' USING ERRCODE='23514'; END IF;
  IF NEW.privacy_safe AND NEW.payload IS NOT NULL THEN
   data:=NEW.payload::jsonb;
   IF NOT coalesce(data->>'privacySuppressed'='true',FALSE) OR coalesce(data->>'orderId'<>subject::text,TRUE)
    OR (data-ARRAY['eventId','eventType','invoiceId','orderId','invoiceNumber','grossAmount','currency','createdAt','correctionId','correctionNumber','originalInvoiceNumber','correctionGross','finalGross','balanceModel','balanceVersion','privacySuppressed'])<>'{}'::jsonb
    OR NEW.last_error IS NOT NULL AND NEW.last_error<>'Event broker is unavailable' THEN RAISE EXCEPTION 'Private invoice fields after privacy fence' USING ERRCODE='23514'; END IF;
   IF TG_OP='INSERT' THEN RETURN NEW; END IF;
   IF OLD.privacy_safe AND NEW.payload=OLD.payload
    AND (to_jsonb(NEW)-ARRAY['status','attempts','last_error','next_attempt_at','published_at','processing_token','processing_until']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','attempts','last_error','next_attempt_at','published_at','processing_token','processing_until']) THEN RETURN NEW; END IF;
  END IF;
  IF TG_OP='UPDATE' THEN
   IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
   IF NEW.payload IS NULL AND NEW.last_error IS NULL AND NEW.payload_compacted_at IS NOT NULL
    AND (to_jsonb(NEW)-ARRAY['payload','last_error','payload_compacted_at']) IS NOT DISTINCT FROM (to_jsonb(OLD)-ARRAY['payload','last_error','payload_compacted_at']) THEN RETURN NEW; END IF;
  END IF;
  RAISE EXCEPTION 'Invoice privacy record is read only' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END;$$;
CREATE TRIGGER finance_privacy_write BEFORE INSERT OR UPDATE OR DELETE ON invoice_outbox_events FOR EACH ROW EXECUTE FUNCTION billing_privacy_outbox_guard();

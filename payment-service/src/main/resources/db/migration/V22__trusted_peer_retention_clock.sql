-- Local source clocks only. No historical backfill, expiry or business redaction.
ALTER TABLE commerce_retention_source_state ADD COLUMN local_open_seen BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE commerce_retention_source_state ADD COLUMN local_reconciled_at TIMESTAMPTZ;
ALTER TABLE commerce_retention_source_state ADD CONSTRAINT peer_open_clock CHECK(NOT local_open_seen OR local_reconciled_at IS NULL);
CREATE FUNCTION commerce_retention_peer_ready(p_order BIGINT) RETURNS boolean LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM payments p WHERE p.order_id=p_order AND p.status IN ('SUCCEEDED','FAILED')
 AND p.request_fingerprint IS NOT NULL AND p.result_recorded_at IS NOT NULL AND p.processing_token IS NULL AND p.processing_until IS NULL
 AND (p.status<>'FAILED' OR p.provider_declined) AND p.checkout_session_id IS NULL AND p.stripe_intent_id IS NULL
 AND p.stripe_external_refund_id IS NULL AND NOT p.stripe_external_hold AND NOT p.stripe_compensation_required
 AND (p.status<>'SUCCEEDED' OR p.provider_reference LIKE 'SIM-%' OR p.provider_reference LIKE 'CASH-%'))
 AND NOT EXISTS(SELECT 1 FROM payment_refunds WHERE order_id=p_order)
 AND NOT EXISTS(SELECT 1 FROM stripe_webhook_receipts x JOIN payments p ON p.id=x.payment_id WHERE p.order_id=p_order)
 AND NOT EXISTS(SELECT 1 FROM payment_follow_ups WHERE order_id=p_order AND (status NOT IN ('SUCCEEDED','CANCELLED') OR processing_token IS NOT NULL OR processing_until IS NOT NULL))
 AND EXISTS(SELECT 1 FROM payment_outbox_events WHERE order_id=p_order)
 AND (SELECT count(*) FROM payment_outbox_events WHERE order_id=p_order)<=100
 AND NOT EXISTS(SELECT 1 FROM payment_outbox_events WHERE order_id=p_order AND (status<>'PUBLISHED' OR published_at IS NULL OR processing_token IS NOT NULL OR processing_until IS NOT NULL));
$$;

CREATE FUNCTION protect_peer_retention_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retention source history is retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.local_open_seen OR NEW.local_reconciled_at IS NOT NULL THEN RAISE EXCEPTION 'No supplied reconciliation clock' USING ERRCODE='23514'; END IF;
 ELSIF NEW.order_id<>OLD.order_id OR (OLD.restore_blocked AND NOT NEW.restore_blocked)
  OR (pg_trigger_depth()<2 AND (NEW.local_open_seen,NEW.local_reconciled_at) IS DISTINCT FROM (OLD.local_open_seen,OLD.local_reconciled_at)) THEN
  RAISE EXCEPTION 'Retention clock is source controlled' USING ERRCODE='23514';
 END IF; RETURN NEW;
END; $$;
CREATE TRIGGER peer_retention_clock_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_source_state FOR EACH ROW EXECUTE FUNCTION protect_peer_retention_clock();
CREATE FUNCTION initialize_peer_retention_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE commerce_retention_source_state SET local_open_seen=NOT commerce_retention_peer_ready(NEW.order_id) WHERE order_id=NEW.order_id;
 RETURN NEW;
END; $$;
CREATE TRIGGER peer_retention_clock_init AFTER INSERT ON commerce_retention_source_state FOR EACH ROW EXECUTE FUNCTION initialize_peer_retention_clock();
CREATE FUNCTION refresh_peer_retention_clock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ready BOOLEAN; current_revision BIGINT; blocked BOOLEAN;
BEGIN
 SELECT source_revision,restore_blocked INTO current_revision,blocked FROM commerce_retention_source_state WHERE order_id=NEW.order_id FOR UPDATE;
 -- Deferred execution sees the completed business transaction. Only its last
 -- invalidation revision computes a clock; intermediate trigger rows cannot reset it.
 IF NOT FOUND OR blocked OR current_revision<>NEW.source_revision THEN RETURN NEW; END IF;
 ready:=commerce_retention_peer_ready(NEW.order_id);
 UPDATE commerce_retention_source_state SET local_reconciled_at=CASE WHEN ready AND local_open_seen THEN clock_timestamp() ELSE NULL END,
  local_open_seen=NOT ready WHERE order_id=NEW.order_id;
 RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER peer_retention_clock_commit AFTER UPDATE ON commerce_retention_source_state DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW WHEN (OLD.source_revision IS DISTINCT FROM NEW.source_revision) EXECUTE FUNCTION refresh_peer_retention_clock();

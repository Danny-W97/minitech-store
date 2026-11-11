-- Local source clocks only. No historical backfill, expiry or business redaction.
ALTER TABLE commerce_retention_source_state ADD COLUMN local_open_seen BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE commerce_retention_source_state ADD COLUMN local_reconciled_at TIMESTAMPTZ;
ALTER TABLE commerce_retention_source_state ADD CONSTRAINT peer_open_clock CHECK(NOT local_open_seen OR local_reconciled_at IS NULL);
CREATE FUNCTION commerce_retention_peer_ready(p_order BIGINT) RETURNS boolean LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(SELECT 1 FROM notifications WHERE source_id=p_order AND event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED'))
 AND NOT EXISTS(SELECT 1 FROM notifications WHERE source_id=p_order AND event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED') AND (status<>'SENT' OR sent_at IS NULL OR processing_token IS NOT NULL OR processing_until IS NOT NULL OR next_attempt_at IS NOT NULL))
 AND NOT EXISTS(SELECT 1 FROM notification_dead_letters d WHERE d.source_id=p_order AND d.event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED') AND (d.status<>'RESOLVED' OR d.resolved_at IS NULL OR NOT EXISTS(SELECT 1 FROM notifications n WHERE n.id=d.notification_id AND n.source_id=d.source_id AND n.event_type=d.event_type)))
 AND NOT EXISTS(SELECT 1 FROM notification_dead_letters d JOIN notifications n ON n.id=d.notification_id WHERE n.source_id=p_order AND n.event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED') AND (d.source_id<>n.source_id OR d.event_type<>n.event_type OR d.status<>'RESOLVED' OR d.resolved_at IS NULL))
 AND EXISTS(SELECT 1 FROM event_inbox WHERE order_id=p_order AND status='ACCEPTED')
 AND NOT EXISTS(SELECT 1 FROM event_inbox WHERE order_id=p_order AND status<>'ACCEPTED')
 AND NOT EXISTS(SELECT 1 FROM event_inbox e JOIN notifications n ON n.id=e.related_id WHERE e.order_id IS NULL AND n.source_id=p_order AND n.event_type IN ('ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED'))
 AND (SELECT count(*) FROM event_inbox WHERE order_id=p_order)<=100;
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

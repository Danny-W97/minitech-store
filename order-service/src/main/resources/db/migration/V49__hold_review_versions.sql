-- Diagnostic versions only. Expiry does not delete retained replay history or grant authority.
CREATE TABLE commerce_retention_hold_review_versions (
 review_id UUID PRIMARY KEY,
 request_id UUID NOT NULL REFERENCES commerce_retention_hold_requests(request_id),
 ordinal SMALLINT NOT NULL CHECK(ordinal BETWEEN 1 AND 8),
 protocol VARCHAR(40) NOT NULL CHECK(protocol='HOLD_RESTORATION_REVIEW_V1'),
 scope_hash CHAR(64) NOT NULL CHECK(scope_hash ~ '^[0-9a-f]{64}$'),
 history_hash CHAR(64) NOT NULL CHECK(history_hash ~ '^[0-9a-f]{64}$'),
 result_hash CHAR(64) NOT NULL CHECK(result_hash ~ '^[0-9a-f]{64}$'),
 version_hash CHAR(64) NOT NULL CHECK(version_hash ~ '^[0-9a-f]{64}$'),
 reviewed_at TIMESTAMPTZ NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(request_id,ordinal),
 CHECK(expires_at>reviewed_at AND expires_at<=reviewed_at+interval '15 seconds')
);
CREATE FUNCTION protect_hold_review_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT; blocked BOOLEAN; next_ordinal INT;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Review versions are immutable' USING ERRCODE='23514'; END IF;
 SELECT order_id,restore_blocked INTO subject,blocked FROM commerce_retention_hold_requests WHERE request_id=NEW.request_id FOR UPDATE;
 IF NOT FOUND OR blocked OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=subject) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=subject)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=subject AND restore_blocked) THEN
  RAISE EXCEPTION 'Review version scope unavailable' USING ERRCODE='23514'; END IF;
 SELECT coalesce(max(ordinal),0)+1 INTO next_ordinal FROM commerce_retention_hold_review_versions WHERE request_id=NEW.request_id;
 IF NEW.ordinal<>next_ordinal OR NEW.reviewed_at<clock_timestamp()-interval '15 seconds' OR NEW.reviewed_at>clock_timestamp()+interval '5 seconds' OR NEW.expires_at<=clock_timestamp() THEN
  RAISE EXCEPTION 'Review version sequence or lifetime invalid' USING ERRCODE='23514'; END IF;
 NEW.created_at:=clock_timestamp();
 RETURN NEW;
END;
$$;
CREATE TRIGGER hold_review_version_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_review_versions FOR EACH ROW EXECUTE FUNCTION protect_hold_review_version();

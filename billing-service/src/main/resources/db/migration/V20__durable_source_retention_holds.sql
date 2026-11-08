-- Known purchase blockers only. No release, expiry, complete coverage or positive clearance.
CREATE TABLE commerce_retention_source_holds (
 hold_id UUID PRIMARY KEY,
 order_id BIGINT NOT NULL REFERENCES commerce_retention_source_state(order_id),
 guard VARCHAR(40) NOT NULL CHECK(guard IN ('NO_PRESERVATION_HOLD','NO_OPEN_OR_REOPENED_CASE','NO_ACTIVE_RETRY_OR_DLQ')),
 reason_code VARCHAR(24) NOT NULL CHECK(reason_code IN ('PRESERVATION_REQUEST','OPEN_CASE','DEPENDENCY_REVIEW')),
 request_id UUID NOT NULL UNIQUE,
 source_revision BIGINT NOT NULL DEFAULT 0 CHECK(source_revision>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_retention_source_hold_order ON commerce_retention_source_holds(order_id,guard);
CREATE FUNCTION protect_source_retention_hold() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_revision BIGINT;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Source hold history is immutable' USING ERRCODE='23514'; END IF;
 IF NEW.source_revision<>0 THEN RAISE EXCEPTION 'Source hold revision is database controlled' USING ERRCODE='23514'; END IF;
 PERFORM pg_advisory_xact_lock(436,hashint8(NEW.order_id));
 SELECT source_revision INTO current_revision FROM commerce_retention_source_state WHERE order_id=NEW.order_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Unknown source purchase' USING ERRCODE='23514'; END IF;
 NEW.source_revision:=current_revision+1;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_source_hold_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_source_holds
 FOR EACH ROW EXECUTE FUNCTION protect_source_retention_hold();
CREATE FUNCTION invalidate_source_for_retention_hold() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- Negative evidence must invalidate a live observation as well; it is not a business mutation.
 PERFORM queue_commerce_retention_change(NEW.order_id);
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_source_hold_inserted AFTER INSERT ON commerce_retention_source_holds
 FOR EACH ROW EXECUTE FUNCTION invalidate_source_for_retention_hold();

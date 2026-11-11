-- Scoped source repair receipts only; signed case authorization is checked by the receiver.
CREATE TABLE commerce_retention_hold_restoration_receipts (
 intent_id UUID PRIMARY KEY,
 case_authorization_id UUID NOT NULL,
 command_hash CHAR(64) NOT NULL CHECK(command_hash ~ '^[0-9a-f]{64}$'),
 order_id BIGINT NOT NULL REFERENCES commerce_retention_source_state(order_id),
 source_request_id UUID NOT NULL,
 hold_id UUID NOT NULL REFERENCES commerce_retention_source_holds(hold_id),
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 source_revision BIGINT NOT NULL CHECK(source_revision>0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_hold_restoration_order ON commerce_retention_hold_restoration_receipts(order_id);
CREATE FUNCTION protect_hold_restoration_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE epoch BIGINT; revision BIGINT; blocked BOOLEAN;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Hold restoration receipts are immutable' USING ERRCODE='23514'; END IF;
 PERFORM pg_advisory_xact_lock(436,hashint8(NEW.order_id));
 SELECT binding_epoch,source_revision,restore_blocked INTO epoch,revision,blocked FROM commerce_retention_source_state WHERE order_id=NEW.order_id FOR UPDATE;
 IF NOT FOUND OR blocked OR NEW.binding_epoch<>epoch OR NEW.source_revision<>revision
  OR NOT EXISTS(SELECT 1 FROM commerce_retention_source_holds h WHERE h.hold_id=NEW.hold_id AND h.order_id=NEW.order_id AND h.request_id=NEW.source_request_id AND h.source_revision<=NEW.source_revision) THEN
  RAISE EXCEPTION 'Invalid or quarantined hold restoration receipt' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_restoration_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_restoration_receipts
 FOR EACH ROW EXECUTE FUNCTION protect_hold_restoration_receipt();

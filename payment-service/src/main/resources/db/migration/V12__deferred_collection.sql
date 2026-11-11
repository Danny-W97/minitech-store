ALTER TABLE payments ADD COLUMN collection_reference VARCHAR(80);
ALTER TABLE payments ADD COLUMN collected_at TIMESTAMPTZ;
ALTER TABLE payments ADD CONSTRAINT uk_payment_collection_reference UNIQUE(collection_reference);
-- Historical PENDING cash responses did not establish fulfillment. They must
-- pass the new admission protocol on an explicit retry, never be treated as paid.
UPDATE payments SET result_recorded_at=NULL
WHERE status='PENDING' AND payment_method IN ('CASH_ON_DELIVERY','CASH_AT_PICKUP');
ALTER TABLE payment_follow_ups DROP CONSTRAINT ck_payment_follow_up_kind;
ALTER TABLE payment_follow_ups ADD CONSTRAINT ck_payment_follow_up_kind CHECK (kind IN (
    'PROMOTION_REDEMPTION','SHIPMENT_TRACKING','INVOICE_ISSUE','PAYMENT_NOTIFICATION',
    'PAYMENT_PENDING_NOTIFICATION','ORDER_SETTLEMENT'
));

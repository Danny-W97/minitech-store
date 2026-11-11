-- Existing orders remain untouched; legacy coupon reservations require explicit reconciliation.
ALTER TABLE orders ADD COLUMN coupon_expires_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN coupon_reservation_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN coupon_next_attempt_at TIMESTAMPTZ;
CREATE INDEX idx_orders_coupon_expiry ON orders(coupon_next_attempt_at,id)
WHERE coupon_expires_at IS NOT NULL AND status IN ('CREATED','CONFIRMED')
  AND deferred_payment_method IS NULL AND refund_payment_id IS NULL AND lifecycle_review_required=false;

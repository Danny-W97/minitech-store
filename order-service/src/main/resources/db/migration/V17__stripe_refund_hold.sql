ALTER TABLE orders ADD COLUMN refund_payment_id BIGINT;
ALTER TABLE orders ADD COLUMN refund_state VARCHAR(24);

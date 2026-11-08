ALTER TABLE orders ADD COLUMN deferred_payment_method VARCHAR(30);
ALTER TABLE orders ADD COLUMN deferred_payment_id BIGINT;
ALTER TABLE orders ADD COLUMN collection_reference VARCHAR(80);

-- Intent cannot be reconstructed from prices/default dates in historic orders.
-- NULL marks legacy keys: authorized GET remains available, POST replay requires review.
ALTER TABLE orders ADD COLUMN checkout_request_hash VARCHAR(64);
ALTER TABLE orders ADD COLUMN checkout_principal VARCHAR(80);
ALTER TABLE orders ADD CONSTRAINT chk_checkout_intent_pair CHECK (
    (checkout_request_hash IS NULL AND checkout_principal IS NULL)
    OR (checkout_request_hash IS NOT NULL AND checkout_principal IS NOT NULL));

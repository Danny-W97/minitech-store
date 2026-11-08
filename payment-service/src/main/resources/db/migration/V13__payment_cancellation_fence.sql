CREATE TABLE payment_order_gates (
    order_id BIGINT PRIMARY KEY,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE
);
-- Historical FAILED did not distinguish rejection from a post-authorization
-- dependency failure. Never backfill them as a proven provider rejection.
ALTER TABLE payments ADD COLUMN provider_declined BOOLEAN NOT NULL DEFAULT FALSE;

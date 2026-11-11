CREATE TABLE coupon_reservations (
 order_id BIGINT PRIMARY KEY,
 version BIGINT NOT NULL DEFAULT 0,
 code VARCHAR(40) NOT NULL,
 subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal>=0),
 discount_amount NUMERIC(19,2) NOT NULL CHECK (discount_amount>=0),
 state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED','CONSUMED','RELEASED')),
 accepted_at TIMESTAMPTZ, expires_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_coupon_reservations_capacity ON coupon_reservations(code,state);

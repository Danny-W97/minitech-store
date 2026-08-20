ALTER TABLE promotion_codes ADD COLUMN minimum_order_value NUMERIC(19,2) NOT NULL DEFAULT 0;
ALTER TABLE promotion_codes ADD COLUMN maximum_discount_amount NUMERIC(19,2);

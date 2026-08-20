ALTER TABLE orders ADD COLUMN delivery_method VARCHAR(40);
ALTER TABLE orders ADD COLUMN delivery_cost NUMERIC(19, 2);

UPDATE orders
SET delivery_method = 'STANDARD_COURIER',
    delivery_cost = 0.00
WHERE delivery_method IS NULL;

ALTER TABLE orders ALTER COLUMN delivery_method SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_cost SET NOT NULL;

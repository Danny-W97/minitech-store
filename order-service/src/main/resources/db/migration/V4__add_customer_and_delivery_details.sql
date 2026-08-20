ALTER TABLE orders ADD COLUMN customer_id BIGINT;
ALTER TABLE orders ADD COLUMN customer_email VARCHAR(255);
ALTER TABLE orders ADD COLUMN delivery_first_name VARCHAR(100);
ALTER TABLE orders ADD COLUMN delivery_last_name VARCHAR(100);
ALTER TABLE orders ADD COLUMN delivery_street VARCHAR(200);
ALTER TABLE orders ADD COLUMN delivery_postal_code VARCHAR(20);
ALTER TABLE orders ADD COLUMN delivery_city VARCHAR(120);
ALTER TABLE orders ADD COLUMN delivery_country VARCHAR(120);

UPDATE orders
SET customer_email = 'legacy@minitech.local',
    delivery_first_name = 'Legacy',
    delivery_last_name = 'Customer',
    delivery_street = 'Not provided',
    delivery_postal_code = '00-000',
    delivery_city = 'Not provided',
    delivery_country = 'PL'
WHERE customer_email IS NULL;

ALTER TABLE orders ALTER COLUMN customer_email SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_first_name SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_last_name SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_street SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_postal_code SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_city SET NOT NULL;
ALTER TABLE orders ALTER COLUMN delivery_country SET NOT NULL;

CREATE INDEX idx_orders_customer_id ON orders (customer_id);

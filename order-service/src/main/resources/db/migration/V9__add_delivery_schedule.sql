ALTER TABLE orders ADD COLUMN fulfillment_type VARCHAR(30);
ALTER TABLE orders ADD COLUMN delivery_date DATE;
ALTER TABLE orders ADD COLUMN delivery_slot VARCHAR(40);
ALTER TABLE orders ADD COLUMN pickup_location VARCHAR(80);

UPDATE orders
SET fulfillment_type = CASE
        WHEN UPPER(delivery_method) = 'STORE_PICKUP' THEN 'STORE_PICKUP'
        ELSE 'HOME_DELIVERY'
    END
WHERE fulfillment_type IS NULL;

ALTER TABLE orders ALTER COLUMN fulfillment_type SET NOT NULL;

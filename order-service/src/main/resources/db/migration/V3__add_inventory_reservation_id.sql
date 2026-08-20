ALTER TABLE orders ADD COLUMN inventory_reservation_id BIGINT;

CREATE INDEX idx_orders_inventory_reservation_id
    ON orders (inventory_reservation_id);

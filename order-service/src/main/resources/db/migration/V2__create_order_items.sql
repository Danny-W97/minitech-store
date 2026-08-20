ALTER TABLE orders ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN quantity DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN unit_price DROP NOT NULL;

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders (id),
    product_id BIGINT NOT NULL,
    sku VARCHAR(100) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price >= 0),
    total_price NUMERIC(19, 2) NOT NULL CHECK (total_price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

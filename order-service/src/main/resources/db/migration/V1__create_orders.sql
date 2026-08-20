CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price >= 0),
    total_price NUMERIC(19, 2) NOT NULL CHECK (total_price >= 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_product_id ON orders (product_id);

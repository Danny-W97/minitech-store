CREATE TABLE wishlist_items (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_wishlist_customer_product UNIQUE (customer_id, product_id)
);

CREATE INDEX idx_wishlist_customer_created
    ON wishlist_items (customer_id, created_at DESC);

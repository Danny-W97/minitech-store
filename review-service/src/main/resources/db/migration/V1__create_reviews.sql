CREATE TABLE reviews (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title VARCHAR(120) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    moderation_note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_review_customer_order_product UNIQUE (customer_id, order_id, product_id)
);

CREATE INDEX idx_reviews_product_status_created
    ON reviews (product_id, status, created_at DESC);
CREATE INDEX idx_reviews_status_created
    ON reviews (status, created_at ASC);

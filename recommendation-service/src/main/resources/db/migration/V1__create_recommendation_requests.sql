CREATE TABLE recommendation_requests (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT,
    source_product_id BIGINT,
    strategy VARCHAR(40) NOT NULL,
    result_count INTEGER NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_recommendation_requests_requested_at
    ON recommendation_requests (requested_at DESC);
CREATE INDEX idx_recommendation_requests_customer
    ON recommendation_requests (customer_id, requested_at DESC);

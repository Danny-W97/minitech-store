CREATE TABLE return_requests (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_email VARCHAR(255) NOT NULL,
    request_type VARCHAR(20) NOT NULL,
    reason VARCHAR(120) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    moderation_note VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_return_order_customer_type UNIQUE (order_id, customer_id, request_type)
);
CREATE INDEX idx_return_requests_customer ON return_requests(customer_id, created_at DESC);

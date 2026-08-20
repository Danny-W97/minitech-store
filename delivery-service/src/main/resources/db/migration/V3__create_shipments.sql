CREATE TABLE shipments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    tracking_number VARCHAR(60) NOT NULL UNIQUE,
    carrier VARCHAR(80) NOT NULL,
    method_code VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_shipments_status ON shipments (status);

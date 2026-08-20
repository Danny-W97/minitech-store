CREATE TABLE promotion_campaigns (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    min_discount_percent INTEGER NOT NULL CHECK (min_discount_percent BETWEEN 0 AND 100),
    max_discount_percent INTEGER NOT NULL CHECK (max_discount_percent BETWEEN 0 AND 100),
    promotion_count INTEGER NOT NULL CHECK (promotion_count >= 0),
    new_product_count INTEGER NOT NULL CHECK (new_product_count >= 0),
    active BOOLEAN NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE promotion_price_history (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    campaign_code VARCHAR(60) NOT NULL,
    regular_price NUMERIC(19,2) NOT NULL,
    promotional_price NUMERIC(19,2) NOT NULL,
    discount_percent INTEGER NOT NULL,
    rotation_date DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_promotion_history_product_date
    ON promotion_price_history (product_id, rotation_date DESC);

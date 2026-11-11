ALTER TABLE products ADD COLUMN price_revision BIGINT NOT NULL DEFAULT 0 CHECK (price_revision >= 0);

CREATE TABLE product_price_history_events (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    price_revision BIGINT NOT NULL CHECK (price_revision >= 0),
    layout_revision BIGINT NOT NULL CHECK (layout_revision >= 0),
    source_type VARCHAR(24) NOT NULL CHECK (source_type IN ('BASELINE', 'CREATE', 'PRICE_CHANGE', 'DAILY', 'CATALOG_LAYOUT_CHANGE')),
    campaign_code VARCHAR(60) NOT NULL,
    regular_price NUMERIC(19,2) NOT NULL CHECK (regular_price >= 0),
    effective_price NUMERIC(19,2) NOT NULL CHECK (effective_price >= 0),
    discount_percent INTEGER NOT NULL CHECK (discount_percent BETWEEN 0 AND 100),
    rotation_date DATE NOT NULL,
    catalog_index INTEGER NOT NULL CHECK (catalog_index >= 0),
    catalog_size INTEGER NOT NULL CHECK (catalog_size > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_product_price_history_revision UNIQUE(product_id, rotation_date, price_revision, layout_revision, source_type)
);
CREATE INDEX idx_product_price_history_window
    ON product_price_history_events(product_id, rotation_date DESC, recorded_at DESC);

-- This is a base-price snapshot at migration time, not a claim about earlier prices.
INSERT INTO product_price_history_events(product_id, price_revision, layout_revision, source_type, campaign_code,
    regular_price, effective_price, discount_percent, rotation_date, catalog_index, catalog_size, recorded_at)
SELECT id, 0, coalesce((SELECT revision FROM catalog_layout WHERE id = 1), 0), 'BASELINE', 'LEGACY_BASE_PRICE', price, price, 0,
       (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
       (row_number() OVER (ORDER BY id) - 1)::integer,
       count(*) OVER ()::integer,
       CURRENT_TIMESTAMP
FROM products;

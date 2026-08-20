CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(80) NOT NULL,
    manufacturer VARCHAR(120) NOT NULL,
    model VARCHAR(120) NOT NULL,
    technical_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    price NUMERIC(19, 2) NOT NULL CHECK (price >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_products_category ON products (category);
CREATE INDEX idx_products_manufacturer ON products (manufacturer);

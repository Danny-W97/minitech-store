ALTER TABLE inventory_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE inventory_stock_operations (
    id BIGSERIAL PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL UNIQUE,
    product_id BIGINT NOT NULL REFERENCES inventory_items(product_id),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('RECEIPT','ADJUSTMENT')),
    quantity INTEGER NOT NULL,
    available_before INTEGER NOT NULL CHECK (available_before>=0),
    available_quantity INTEGER NOT NULL CHECK (available_quantity>=0),
    reserved_quantity INTEGER NOT NULL CHECK (reserved_quantity>=0),
    sold_quantity INTEGER NOT NULL CHECK (sold_quantity>=0),
    before_version BIGINT NOT NULL CHECK (before_version>=0),
    version BIGINT NOT NULL CHECK (version>=before_version),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (kind<>'RECEIPT' OR quantity>0),
    CHECK (available_quantity::BIGINT-available_before::BIGINT=quantity::BIGINT)
);
CREATE INDEX idx_stock_operations_product_window ON inventory_stock_operations(product_id,id DESC);

CREATE TABLE inventory_items (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_reservations (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_reservation_items (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES inventory_reservations (id),
    product_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_inventory_reservation_items_reservation_id
    ON inventory_reservation_items (reservation_id);

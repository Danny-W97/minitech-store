ALTER TABLE invoices
    ADD COLUMN delivery_cost NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN discount_amount NUMERIC(19,2) NOT NULL DEFAULT 0.00;

CREATE TABLE invoice_items (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    position_no INTEGER NOT NULL,
    product_id BIGINT NOT NULL,
    sku VARCHAR(100),
    product_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    total_price NUMERIC(19,2) NOT NULL CHECK (total_price >= 0),
    CONSTRAINT uk_invoice_item_position UNIQUE (invoice_id, position_no)
);

CREATE INDEX idx_invoice_items_invoice ON invoice_items(invoice_id, position_no);

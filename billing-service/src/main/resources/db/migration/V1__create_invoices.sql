CREATE TABLE invoices (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE,
    customer_id BIGINT,
    customer_email VARCHAR(255) NOT NULL,
    invoice_number VARCHAR(40) NOT NULL UNIQUE,
    billing_kind VARCHAR(20) NOT NULL,
    buyer_name VARCHAR(200) NOT NULL,
    company_name VARCHAR(200),
    tax_id VARCHAR(30),
    street VARCHAR(200) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    city VARCHAR(120) NOT NULL,
    country VARCHAR(80) NOT NULL,
    net_amount NUMERIC(19,2) NOT NULL,
    vat_amount NUMERIC(19,2) NOT NULL,
    gross_amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_invoices_customer ON invoices(customer_id, created_at DESC);

CREATE TABLE invoice_sequences (
    series VARCHAR(30) PRIMARY KEY,
    next_val BIGINT NOT NULL
);

INSERT INTO invoice_sequences (series, next_val) VALUES
    ('FV/2026', 21),
    ('KOR/2026', 1)
ON CONFLICT (series) DO NOTHING;

CREATE TABLE invoice_corrections (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL,
    correction_number VARCHAR(40) NOT NULL UNIQUE,
    reason VARCHAR(255) NOT NULL,
    original_net NUMERIC(19,2) NOT NULL,
    original_vat NUMERIC(19,2) NOT NULL,
    original_gross NUMERIC(19,2) NOT NULL,
    correction_net NUMERIC(19,2) NOT NULL,
    correction_vat NUMERIC(19,2) NOT NULL,
    correction_gross NUMERIC(19,2) NOT NULL,
    final_net NUMERIC(19,2) NOT NULL,
    final_vat NUMERIC(19,2) NOT NULL,
    final_gross NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_invoice_corrections_invoice ON invoice_corrections(invoice_id);
CREATE INDEX idx_invoice_corrections_created_at ON invoice_corrections(created_at DESC);

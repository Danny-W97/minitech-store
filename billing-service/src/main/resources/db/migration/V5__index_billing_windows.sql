CREATE INDEX idx_invoices_customer_window ON invoices (customer_id, id DESC);
CREATE INDEX idx_invoice_corrections_window ON invoice_corrections (invoice_id, id DESC) INCLUDE (correction_gross);

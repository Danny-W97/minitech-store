-- Preserve already-issued historical snapshots; NULL version identifies their old model.
ALTER TABLE invoice_corrections ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE invoice_corrections ADD COLUMN ledger_version BIGINT;
ALTER TABLE invoice_corrections ADD CONSTRAINT uk_correction_request UNIQUE (invoice_id,idempotency_key);
ALTER TABLE invoice_corrections ADD CONSTRAINT uk_correction_version UNIQUE (invoice_id,ledger_version);
ALTER TABLE invoice_corrections ADD CONSTRAINT ck_correction_ledger CHECK (
    (idempotency_key IS NULL AND ledger_version IS NULL) OR
    (idempotency_key IS NOT NULL AND ledger_version IS NOT NULL AND ledger_version>0));

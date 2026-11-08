-- A receipt is an externally signed, minimal intent; it never contains a status token or contact.
ALTER TABLE account_privacy_requests ADD COLUMN journal_receipt TEXT;
ALTER TABLE account_privacy_requests ADD COLUMN restore_imported BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE account_privacy_requests ADD CONSTRAINT ck_restore_imported_no_status_capability CHECK(NOT restore_imported OR (confirmed_at IS NOT NULL AND phase<>'PREVIEW' AND journal_receipt IS NOT NULL AND status_token_hash IS NULL AND status_expires_at IS NULL));
CREATE INDEX idx_privacy_journal_unacknowledged ON account_privacy_requests(customer_id) WHERE confirmed_at IS NOT NULL AND journal_receipt IS NULL;
CREATE TABLE restore_security_state (
 id INTEGER PRIMARY KEY CHECK(id=1),ledger_id UUID,verification_key_hash VARCHAR(64),auth_epoch VARCHAR(36) NOT NULL,restored_at TIMESTAMPTZ,phase VARCHAR(16) NOT NULL DEFAULT 'NORMAL' CHECK(phase IN ('NORMAL','QUARANTINED'))
);
-- Existing sessions remain valid on deployment. Restore explicitly replaces this epoch.
INSERT INTO restore_security_state(id,auth_epoch) VALUES(1,'legacy');

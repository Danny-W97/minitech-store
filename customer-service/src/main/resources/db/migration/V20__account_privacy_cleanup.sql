-- Existing confirmations do not silently gain consent for the new cleanup policy.
ALTER TABLE account_privacy_requests ADD COLUMN account_cleanup_policy_hash VARCHAR(64);
ALTER TABLE account_privacy_requests ADD COLUMN account_cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_privacy_account_cleanup_pending ON account_privacy_requests(phase,confirmed_at,id) WHERE account_cleanup_pending;
ALTER TABLE account_privacy_services ADD COLUMN cleanup_state VARCHAR(32) NOT NULL DEFAULT 'WAITING_STAGE'
 CHECK(cleanup_state IN ('WAITING_STAGE','PENDING','RUNNING','FAILED','DONE','SEPARATE_PROOF_REQUIRED'));
ALTER TABLE account_privacy_services ADD COLUMN cleanup_attempts INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_attempts BETWEEN 0 AND 8);
ALTER TABLE account_privacy_services ADD COLUMN cleanup_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE account_privacy_services ADD COLUMN cleanup_code VARCHAR(40);

-- A43.35 consent is separate; historical confirmations are never upgraded.
ALTER TABLE account_privacy_requests ADD COLUMN commerce_cleanup_policy_hash VARCHAR(64);
ALTER TABLE account_privacy_requests ADD COLUMN commerce_cleanup_pending BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_privacy_commerce_pending ON account_privacy_requests(phase,confirmed_at,id) WHERE commerce_cleanup_pending;
ALTER TABLE account_privacy_services ADD COLUMN cleanup_cursor BIGINT NOT NULL DEFAULT 0 CHECK(cleanup_cursor>=0);
ALTER TABLE account_privacy_services DROP CONSTRAINT account_privacy_services_cleanup_state_check;
ALTER TABLE account_privacy_services ADD CONSTRAINT account_privacy_services_cleanup_state_check
 CHECK(cleanup_state IN ('WAITING_STAGE','PENDING','RUNNING','FAILED','DONE','SEPARATE_PROOF_REQUIRED','WAITING_PROCESS','WAITING_FINANCE','WAITING_REVIEW'));

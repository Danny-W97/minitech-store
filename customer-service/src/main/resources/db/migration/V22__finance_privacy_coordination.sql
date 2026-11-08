-- Historical confirmations remain outside the newly selected A43.36 policy.
ALTER TABLE account_privacy_requests ADD COLUMN finance_policy_hash VARCHAR(64);
ALTER TABLE account_privacy_requests ADD COLUMN finance_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE account_privacy_requests ADD COLUMN finance_state VARCHAR(16) NOT NULL DEFAULT 'WAITING_STAGE'
 CHECK(finance_state IN ('WAITING_STAGE','PENDING','RUNNING','WAIT','FAILED','DONE'));
ALTER TABLE account_privacy_requests ADD COLUMN finance_attempts INTEGER NOT NULL DEFAULT 0 CHECK(finance_attempts BETWEEN 0 AND 8);
ALTER TABLE account_privacy_requests ADD COLUMN finance_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE account_privacy_requests ADD COLUMN finance_retry_generation BIGINT NOT NULL DEFAULT 0 CHECK(finance_retry_generation>=0);
ALTER TABLE account_privacy_requests ADD COLUMN finance_code VARCHAR(60);
ALTER TABLE account_privacy_requests ADD COLUMN finance_orders BIGINT NOT NULL DEFAULT 0 CHECK(finance_orders>=0);
ALTER TABLE account_privacy_requests ADD COLUMN finance_prepared_orders BIGINT NOT NULL DEFAULT 0 CHECK(finance_prepared_orders>=0 AND finance_prepared_orders<=finance_orders);
CREATE INDEX idx_privacy_finance_pending ON account_privacy_requests(finance_retry_at,id) WHERE finance_pending;

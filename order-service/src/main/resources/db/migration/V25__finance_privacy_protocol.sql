CREATE TABLE finance_privacy_consents (
 customer_id BIGINT PRIMARY KEY CHECK(customer_id>0),request_id UUID NOT NULL UNIQUE,policy_hash VARCHAR(64) NOT NULL,
 retry_generation BIGINT NOT NULL DEFAULT 0 CHECK(retry_generation>=0),scan_after BIGINT NOT NULL DEFAULT 0 CHECK(scan_after>=0)
);
CREATE TABLE finance_privacy_jobs (
 order_id BIGINT PRIMARY KEY REFERENCES orders(id),customer_id BIGINT NOT NULL CHECK(customer_id>0),request_id UUID NOT NULL,
 order_version BIGINT NOT NULL CHECK(order_version>=0),step INTEGER NOT NULL DEFAULT 0 CHECK(step BETWEEN 0 AND 7),
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','WAIT','FAILED','READY','REVIEW')),
 cursor VARCHAR(48) NOT NULL DEFAULT '0-0',attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,run_token UUID,run_until TIMESTAMPTZ,code VARCHAR(60)
);
CREATE INDEX idx_finance_privacy_pending ON finance_privacy_jobs(retry_at,order_id) WHERE state NOT IN ('READY','REVIEW');
CREATE INDEX idx_finance_privacy_customer ON finance_privacy_jobs(customer_id,state);
CREATE TABLE finance_privacy_receipts (
 order_id BIGINT NOT NULL REFERENCES finance_privacy_jobs(order_id),service VARCHAR(40) NOT NULL,
 request_id UUID NOT NULL,order_version BIGINT NOT NULL,
 PRIMARY KEY(order_id,service)
);

CREATE TABLE account_export_jobs (
 id UUID PRIMARY KEY, customer_id BIGINT NOT NULL, request_id UUID NOT NULL,
 state VARCHAR(20) NOT NULL CHECK(state IN ('ACTIVE','READY','PARTIAL','LIMIT_REACHED','CANCELLED','EXPIRED')),
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL, policy_hash VARCHAR(64) NOT NULL,
 recorded_bytes BIGINT NOT NULL DEFAULT 0 CHECK(recorded_bytes>=0),
 part_count INTEGER NOT NULL DEFAULT 0 CHECK(part_count>=0),
 run_token UUID, run_until TIMESTAMPTZ,
 UNIQUE(customer_id,request_id), CHECK(expires_at=created_at+INTERVAL '1 hour')
);
CREATE UNIQUE INDEX idx_account_export_one_active ON account_export_jobs(customer_id)
 WHERE state IN ('ACTIVE','PARTIAL');
CREATE INDEX idx_account_export_owner_window ON account_export_jobs(customer_id,created_at DESC);
CREATE INDEX idx_account_export_expiry ON account_export_jobs(expires_at,id);
CREATE TABLE account_export_tasks (
 id UUID PRIMARY KEY, job_id UUID NOT NULL REFERENCES account_export_jobs(id),
 kind VARCHAR(32) NOT NULL, parent_id BIGINT NOT NULL DEFAULT 0, detail_id BIGINT NOT NULL DEFAULT 0,
 cursor_value BIGINT NOT NULL DEFAULT 0 CHECK(cursor_value>=0),
 state VARCHAR(12) NOT NULL CHECK(state IN ('PENDING','RUNNING','DONE','FAILED')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 request_ids UUID[] NOT NULL DEFAULT '{}', error_code VARCHAR(40),
 UNIQUE(job_id,kind,parent_id,detail_id,cursor_value), CHECK(cardinality(request_ids)<=8)
);
CREATE INDEX idx_account_export_task_window ON account_export_tasks(job_id,state,id);
CREATE TABLE account_export_chunks (
 job_id UUID NOT NULL REFERENCES account_export_jobs(id), sequence_no INTEGER NOT NULL,
 task_id UUID NOT NULL UNIQUE REFERENCES account_export_tasks(id),
 data_json TEXT NOT NULL CHECK(octet_length(data_json)<=524288),
 byte_count INTEGER NOT NULL CHECK(byte_count BETWEEN 1 AND 524288),
 sha256 VARCHAR(64) NOT NULL, read_started_at TIMESTAMPTZ NOT NULL, read_finished_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(job_id,sequence_no)
);

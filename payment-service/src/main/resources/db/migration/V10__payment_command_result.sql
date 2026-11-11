ALTER TABLE payments ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE payments ADD COLUMN result_recorded_at TIMESTAMPTZ;

-- Existing terminal results must never be charged again after rollout.
UPDATE payments SET result_recorded_at=updated_at WHERE status IN ('SUCCEEDED','FAILED');

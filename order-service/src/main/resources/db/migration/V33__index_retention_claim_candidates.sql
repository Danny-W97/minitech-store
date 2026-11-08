-- Candidate indexes only: no data/period/activation change and no new metadata.
CREATE INDEX idx_retention_changed_scopes ON commerce_retention_hold_scopes(scope_type,subject_id,revision) WHERE revision>0;
CREATE INDEX idx_retention_ready_due ON commerce_retention_jobs(policy,next_attempt_at,order_id)
 WHERE current AND attempts<8 AND state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED');
CREATE INDEX idx_retention_customer_revision ON commerce_retention_jobs(customer_id,customer_hold_revision,next_attempt_at,order_id)
 WHERE current AND attempts<8 AND state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW');
CREATE INDEX idx_retention_legacy_revision ON commerce_retention_jobs(next_attempt_at,order_id)
 WHERE current AND attempts<8 AND state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW')
 AND (order_hold_revision<0 OR customer_hold_revision<0);
CREATE INDEX idx_retention_exhausted_lease ON commerce_retention_jobs(run_until,next_attempt_at,order_id,generation)
 WHERE current AND state='LEASED' AND attempts=8;
DROP INDEX idx_retention_hold_claim;

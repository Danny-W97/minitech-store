-- Pseudonymous reconciliation hashes, never an erasure permission; no TTL.
CREATE TABLE commerce_retention_payment_evidence (
 order_id BIGINT NOT NULL,
 generation UUID NOT NULL,
 phase VARCHAR(20) NOT NULL,
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 event_hash VARCHAR(64) NOT NULL CHECK(event_hash ~ '^[0-9a-f]{64}$'),
 event_count INT NOT NULL CHECK(event_count BETWEEN 1 AND 100),
 expectation_hash VARCHAR(64) NOT NULL CHECK(expectation_hash ~ '^[0-9a-f]{64}$'),
 policy VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(order_id,generation,phase),
 FOREIGN KEY(order_id,generation,phase) REFERENCES commerce_retention_jobs(order_id,generation,phase)
);
CREATE FUNCTION retain_payment_retention_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Retention receipt is immutable' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER payment_retention_receipt_immutable BEFORE UPDATE OR DELETE ON commerce_retention_payment_evidence FOR EACH ROW EXECUTE FUNCTION retain_payment_retention_evidence();

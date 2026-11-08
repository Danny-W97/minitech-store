-- Existing observations never claimed preservation clearance. Keep their v1 fingerprints unchanged.
ALTER TABLE commerce_retention_source_receipts
 ADD COLUMN hold_state VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN' CHECK(hold_state IN ('UNKNOWN','BLOCKED'));

-- Seven remote sources, including Inventory. History remains immutable and non-authoritative.
-- The primary key still bounds history to one observation per source/job generation/phase.
ALTER TABLE commerce_retention_source_receipts
 DROP CONSTRAINT commerce_retention_source_receipts_source_check;
ALTER TABLE commerce_retention_source_receipts
 ADD CONSTRAINT commerce_retention_source_receipts_source_check
 CHECK(source IN ('RETURNS','PAYMENT','INVENTORY','PROMOTION','DELIVERY','BILLING','NOTIFICATION'));

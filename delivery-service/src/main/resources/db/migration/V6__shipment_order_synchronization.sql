ALTER TABLE shipments ADD COLUMN progress_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE shipments ADD COLUMN sync_state VARCHAR(24) NOT NULL DEFAULT 'IDLE';
ALTER TABLE shipments ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE shipments ADD COLUMN sync_error VARCHAR(80);
ALTER TABLE shipments ADD COLUMN sync_next_attempt_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN sync_token VARCHAR(36);
ALTER TABLE shipments ADD COLUMN sync_until TIMESTAMPTZ;
UPDATE shipments SET sync_state='PENDING',sync_next_attempt_at=CURRENT_TIMESTAMP
WHERE status IN ('IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','PICKED_UP','CANCELLED');
ALTER TABLE shipments ADD CONSTRAINT ck_shipment_sync_state CHECK (sync_state IN ('IDLE','PENDING','RETRY','SYNCED','REVIEW_REQUIRED'));
CREATE INDEX idx_shipments_sync_due ON shipments(sync_next_attempt_at,id) WHERE sync_state IN ('PENDING','RETRY');

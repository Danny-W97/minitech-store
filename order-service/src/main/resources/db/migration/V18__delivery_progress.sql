ALTER TABLE orders ADD COLUMN delivery_tracking_number VARCHAR(60);
ALTER TABLE orders ADD COLUMN delivery_revision BIGINT NOT NULL DEFAULT -1;
ALTER TABLE orders ADD COLUMN delivery_status VARCHAR(30);

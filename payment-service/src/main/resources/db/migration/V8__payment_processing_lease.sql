ALTER TABLE payments ADD COLUMN processing_token VARCHAR(36);
ALTER TABLE payments ADD COLUMN processing_until TIMESTAMPTZ;

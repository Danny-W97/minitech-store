ALTER TABLE notifications ADD COLUMN processing_token VARCHAR(36);
ALTER TABLE notifications ADD COLUMN processing_until TIMESTAMPTZ;
CREATE INDEX idx_notifications_processing_due ON notifications (status, processing_until, id);

CREATE INDEX idx_notifications_source_window ON notifications (source_id, id DESC);
CREATE INDEX idx_notifications_status_window ON notifications (status, id DESC);
CREATE INDEX idx_notification_dead_letters_status_window ON notification_dead_letters (status, id);

ALTER TABLE notifications ALTER COLUMN message TYPE VARCHAR(6000);
ALTER TABLE notification_dead_letters ALTER COLUMN message TYPE VARCHAR(6000);

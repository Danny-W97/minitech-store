ALTER TABLE inventory_reservations
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE inventory_reservations
SET expires_at = created_at + INTERVAL '15 minutes'
WHERE expires_at IS NULL;

ALTER TABLE inventory_reservations
    ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_inventory_reservations_expiration
    ON inventory_reservations (status, expires_at);

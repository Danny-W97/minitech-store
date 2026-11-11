ALTER TABLE refresh_tokens ADD COLUMN family_id VARCHAR(64);
UPDATE refresh_tokens SET family_id = 'legacy-' || id::text;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

-- Unfinished legacy enrollments deliberately require starting again after upgrade.
ALTER TABLE customer_totp_methods ADD COLUMN enrollment_expires_at TIMESTAMPTZ;

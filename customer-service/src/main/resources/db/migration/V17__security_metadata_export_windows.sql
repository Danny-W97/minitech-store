-- Public paging identity is separate from the authenticator credential identifier.
ALTER TABLE user_credentials ADD COLUMN export_id BIGSERIAL NOT NULL;
CREATE UNIQUE INDEX idx_user_credentials_export_id ON user_credentials(export_id);
CREATE INDEX idx_user_credentials_export_window ON user_credentials(user_entity_user_id, export_id DESC);
CREATE INDEX idx_recovery_metadata_export_window ON customer_recovery_codes(customer_id, id DESC);

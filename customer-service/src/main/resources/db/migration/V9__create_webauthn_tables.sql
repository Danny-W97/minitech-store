CREATE TABLE user_credentials (
    credential_id BYTEA PRIMARY KEY,
    user_entity_user_id BYTEA NOT NULL,
    public_key BYTEA NOT NULL,
    signature_count BIGINT NOT NULL,
    uv_initialized BOOLEAN NOT NULL,
    backup_eligible BOOLEAN NOT NULL,
    authenticator_transports VARCHAR(255),
    public_key_credential_type VARCHAR(40) NOT NULL,
    backup_state BOOLEAN NOT NULL,
    attestation_object BYTEA,
    attestation_client_data_json BYTEA,
    created TIMESTAMPTZ NOT NULL,
    last_used TIMESTAMPTZ,
    label VARCHAR(255)
);

CREATE INDEX idx_user_credentials_user_id ON user_credentials (user_entity_user_id);

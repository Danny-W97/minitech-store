CREATE TABLE customer_totp_methods (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL UNIQUE REFERENCES customers(id) ON DELETE CASCADE,
    secret_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_time_step BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE customer_recovery_codes (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_customer_recovery_codes_hash ON customer_recovery_codes (code_hash);
CREATE INDEX idx_customer_recovery_codes_customer ON customer_recovery_codes (customer_id);

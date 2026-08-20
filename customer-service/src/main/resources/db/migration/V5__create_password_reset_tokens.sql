CREATE TABLE password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_password_reset_tokens_customer_id ON password_reset_tokens (customer_id);
CREATE INDEX idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);

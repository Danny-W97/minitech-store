ALTER TABLE customers
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN verification_token_hash VARCHAR(64),
    ADD COLUMN verification_expires_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uk_customers_verification_token_hash
    ON customers (verification_token_hash)
    WHERE verification_token_hash IS NOT NULL;

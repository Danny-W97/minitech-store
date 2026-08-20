CREATE TABLE order_sagas (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE REFERENCES orders (id),
    state VARCHAR(40) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_sagas_due
    ON order_sagas (state, next_attempt_at);

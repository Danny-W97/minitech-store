CREATE TABLE promotion_redemptions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    order_id BIGINT NOT NULL,
    redeemed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_promotion_redemption_code_order UNIQUE (code, order_id)
);

CREATE INDEX idx_promotion_redemptions_code ON promotion_redemptions (code);

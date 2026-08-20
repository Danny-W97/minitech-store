CREATE TABLE promotion_codes (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    description VARCHAR(200) NOT NULL,
    discount_percent INTEGER NOT NULL CHECK (discount_percent BETWEEN 1 AND 100),
    max_redemptions INTEGER CHECK (max_redemptions IS NULL OR max_redemptions > 0),
    redeemed_count INTEGER NOT NULL DEFAULT 0 CHECK (redeemed_count >= 0),
    active BOOLEAN NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO promotion_codes
    (code, description, discount_percent, max_redemptions, redeemed_count, active,
     valid_from, valid_to, created_at, updated_at)
VALUES
    ('WELCOME10', 'Rabat powitalny dla nowych klientów', 10, NULL, 0, TRUE,
     DATE '2025-01-01', DATE '2099-12-31', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('MINITECH15', 'Rabat testowy MiniTech Store', 15, NULL, 0, TRUE,
     DATE '2025-01-01', DATE '2099-12-31', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

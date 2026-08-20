INSERT INTO promotion_campaigns (
    code, name, min_discount_percent, max_discount_percent, promotion_count, new_product_count,
    active, valid_from, valid_to, created_at, updated_at
) VALUES (
    'DAILY_STORE_ROTATION', 'Codzienna rotacja promocji i nowości', 5, 50, 12, 8,
    TRUE, DATE '2025-01-01', DATE '2099-12-31', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

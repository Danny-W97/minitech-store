UPDATE inventory_items
SET available_quantity = GREATEST(
        0,
        CASE product_id
            WHEN 1 THEN 12
            WHEN 2 THEN 10
            WHEN 3 THEN 5
        END - reserved_quantity
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE product_id IN (1, 2, 3);

INSERT INTO inventory_items (
    product_id, available_quantity, reserved_quantity, created_at, updated_at
)
VALUES
    (4, 8, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (5, 7, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (6, 15, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (7, 6, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (8, 9, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (9, 11, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (product_id) DO NOTHING;

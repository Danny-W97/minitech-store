INSERT INTO inventory_items (
    product_id, available_quantity, reserved_quantity, created_at, updated_at
)
VALUES
    (1, 12, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, 20, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, 5, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

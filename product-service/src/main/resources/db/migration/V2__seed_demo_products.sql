INSERT INTO products (
    sku, name, category, manufacturer, model, technical_parameters,
    price, active, created_at, updated_at
)
VALUES
(
    'SSD-SAM-990PRO-2TB',
    'Samsung 990 PRO 2TB',
    'SSD',
    'Samsung',
    '990 PRO',
    '{"capacity": "2TB", "interface": "PCIe 4.0 NVMe", "readSpeedMBps": 7450}'::jsonb,
    699.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'RAM-KNG-FURY-32GB',
    'Kingston Fury Beast 32GB',
    'RAM',
    'Kingston',
    'Fury Beast DDR5',
    '{"capacity": "32GB", "type": "DDR5", "speedMHz": 6000}'::jsonb,
    499.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'GPU-ASU-RTX4070S',
    'ASUS GeForce RTX 4070 SUPER',
    'GPU',
    'ASUS',
    'Dual RTX 4070 SUPER 12GB',
    '{"memory": "12GB GDDR6X", "interface": "PCIe 4.0", "rayTracing": true}'::jsonb,
    2899.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);

UPDATE products
SET image_url = CASE category
    WHEN 'CPU' THEN '/assets/products/cpu.svg'
    WHEN 'GPU' THEN '/assets/products/gpu.svg'
    WHEN 'RAM' THEN '/assets/products/ram.svg'
    WHEN 'SSD' THEN '/assets/products/ssd.svg'
    WHEN 'Płyta główna' THEN '/assets/products/motherboard.svg'
    WHEN 'Monitor' THEN '/assets/products/monitor.svg'
    WHEN 'Laptop' THEN '/assets/products/laptop.svg'
    WHEN 'Klawiatura' THEN '/assets/products/keyboard.svg'
    ELSE '/assets/products/tech.svg'
END
WHERE image_url IS NULL;

INSERT INTO products (
    sku, name, category, manufacturer, model, technical_parameters,
    price, active, image_url, created_at, updated_at
)
VALUES
(
    'CPU-INTEL-I7-14700K',
    'Intel Core i7-14700K',
    'CPU',
    'Intel',
    'Core i7-14700K',
    '{"cores": 20, "threads": 28, "socket": "LGA1700", "boostGHz": 5.6}'::jsonb,
    1799.99,
    TRUE,
    '/assets/products/cpu.svg',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'GPU-MSI-RTX4060TI',
    'MSI GeForce RTX 4060 Ti Ventus',
    'GPU',
    'MSI',
    'RTX 4060 Ti 8G',
    '{"memory": "8GB GDDR6", "interface": "PCIe 4.0", "rayTracing": true}'::jsonb,
    1899.99,
    TRUE,
    '/assets/products/gpu.svg',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'RAM-COR-VENGEANCE-64GB',
    'Corsair Vengeance 64GB',
    'RAM',
    'Corsair',
    'Vengeance DDR5 6000',
    '{"capacity": "64GB", "type": "DDR5", "speedMHz": 6000}'::jsonb,
    899.99,
    TRUE,
    '/assets/products/ram.svg',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'SSD-KNG-KC3000-2TB',
    'Kingston KC3000 2TB',
    'SSD',
    'Kingston',
    'KC3000',
    '{"capacity": "2TB", "interface": "PCIe 4.0 NVMe", "readSpeedMBps": 7000}'::jsonb,
    529.99,
    TRUE,
    '/assets/products/ssd.svg',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'MON-DELL-G2724D',
    'Dell G2724D',
    'Monitor',
    'Dell',
    'G2724D',
    '{"size": "27 inch", "resolution": "2560x1440", "refreshHz": 165, "panel": "Fast IPS"}'::jsonb,
    1199.99,
    TRUE,
    '/assets/products/monitor.svg',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'AUDIO-SONY-WH1000XM5',
    'Sony WH-1000XM5',
    'Słuchawki',
    'Sony',
    'WH-1000XM5',
    '{"type": "bezprzewodowe", "noiseCancelling": true, "batteryHours": 30}'::jsonb,
    1299.99,
    TRUE,
    '/assets/products/audio.svg',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (sku) DO NOTHING;

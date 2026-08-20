INSERT INTO products (
    sku, name, category, manufacturer, model, technical_parameters,
    price, active, created_at, updated_at
)
VALUES
(
    'CPU-AMD-7800X3D',
    'AMD Ryzen 7 7800X3D',
    'CPU',
    'AMD',
    'Ryzen 7 7800X3D',
    '{"cores": 8, "threads": 16, "socket": "AM5", "boostGHz": 5.0}'::jsonb,
    1599.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'MB-MSI-B650-TOMAHAWK',
    'MSI MAG B650 TOMAHAWK WIFI',
    'Płyta główna',
    'MSI',
    'MAG B650 TOMAHAWK WIFI',
    '{"socket": "AM5", "memory": "DDR5", "formFactor": "ATX", "wifi": true}'::jsonb,
    899.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'SSD-WD-SN850X-1TB',
    'WD Black SN850X 1TB',
    'SSD',
    'Western Digital',
    'Black SN850X',
    '{"capacity": "1TB", "interface": "PCIe 4.0 NVMe", "readSpeedMBps": 7300}'::jsonb,
    399.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'MON-LG-27GR83Q',
    'LG UltraGear 27GR83Q-B',
    'Monitor',
    'LG',
    '27GR83Q-B',
    '{"size": "27 inch", "resolution": "2560x1440", "refreshHz": 240, "panel": "IPS"}'::jsonb,
    1399.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'LAP-LEN-LEGION5',
    'Lenovo Legion 5 16',
    'Laptop',
    'Lenovo',
    'Legion 5 16IRX9',
    '{"screen": "16 inch", "memory": "32GB", "storage": "1TB SSD", "gpu": "RTX 4060"}'::jsonb,
    5499.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
),
(
    'KEY-LOG-GPRO-TKL',
    'Logitech G Pro X TKL',
    'Klawiatura',
    'Logitech',
    'G Pro X TKL Lightspeed',
    '{"layout": "US", "switches": "GX Brown", "wireless": true, "backlight": "RGB"}'::jsonb,
    699.99,
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

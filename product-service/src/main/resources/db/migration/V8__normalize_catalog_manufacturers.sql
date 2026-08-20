-- Normalize manufacturer and model metadata for products added in V5.
-- The original seed contained shifted manufacturer values in several categories.
WITH canonical AS (
    SELECT
        id,
        CASE
            WHEN sku LIKE 'CPU-INTEL-%' THEN 'Intel'
            WHEN sku LIKE 'CPU-AMD-%' THEN 'AMD'
            WHEN sku LIKE 'GPU-GIGABYTE-%' THEN 'Gigabyte'
            WHEN sku LIKE 'GPU-SAPPHIRE-%' THEN 'Sapphire'
            WHEN sku LIKE 'GPU-ASROCK-%' THEN 'ASRock'
            WHEN sku LIKE 'GPU-PNY-%' THEN 'PNY'
            WHEN sku LIKE 'GPU-ZOTAC-%' THEN 'Zotac'
            WHEN sku LIKE 'GPU-POWERCOLOR-%' THEN 'PowerColor'
            WHEN sku LIKE 'GPU-GAINWARD-%' THEN 'Gainward'
            WHEN sku LIKE 'GPU-ASUS-%' THEN 'ASUS'
            WHEN sku LIKE 'RAM-G-SKILL-%' THEN 'G.Skill'
            WHEN sku LIKE 'RAM-CRUCIAL-%' THEN 'Crucial'
            WHEN sku LIKE 'RAM-PATRIOT-%' THEN 'Patriot'
            WHEN sku LIKE 'RAM-TEAMGROUP-%' THEN 'TeamGroup'
            WHEN sku LIKE 'RAM-GOODRAM-%' THEN 'Goodram'
            WHEN sku LIKE 'RAM-KINGSTON-%' THEN 'Kingston'
            WHEN sku LIKE 'RAM-CORSAIR-%' THEN 'Corsair'
            WHEN sku LIKE 'RAM-ADATA-%' THEN 'ADATA'
            WHEN sku LIKE 'SSD-SAMSUNG-%' THEN 'Samsung'
            WHEN sku LIKE 'SSD-WD-%' THEN 'Western Digital'
            WHEN sku LIKE 'SSD-CRUCIAL-%' THEN 'Crucial'
            WHEN sku LIKE 'SSD-LEXAR-%' THEN 'Lexar'
            WHEN sku LIKE 'SSD-KINGSTON-%' THEN 'Kingston'
            WHEN sku LIKE 'SSD-SEAGATE-%' THEN 'Seagate'
            WHEN sku LIKE 'SSD-GOODRAM-%' THEN 'Goodram'
            WHEN sku LIKE 'MBO-ASUS-%' THEN 'ASUS'
            WHEN sku LIKE 'MBO-GIGABYTE-%' THEN 'Gigabyte'
            WHEN sku LIKE 'MBO-ASROCK-%' THEN 'ASRock'
            WHEN sku LIKE 'MBO-MSI-%' THEN 'MSI'
            WHEN sku LIKE 'LAP-ASUS-%' THEN 'ASUS'
            WHEN sku LIKE 'LAP-HP-%' THEN 'HP'
            WHEN sku LIKE 'LAP-ACER-%' THEN 'Acer'
            WHEN sku LIKE 'LAP-DELL-%' THEN 'Dell'
            WHEN sku LIKE 'LAP-MSI-%' THEN 'MSI'
            WHEN sku LIKE 'LAP-APPLE-%' THEN 'Apple'
            WHEN sku LIKE 'LAP-LENOVO-%' THEN 'Lenovo'
            WHEN sku LIKE 'KEY-RAZER-%' THEN 'Razer'
            WHEN sku LIKE 'KEY-KEYCHRON-%' THEN 'Keychron'
            WHEN sku LIKE 'KEY-STEELSERIES-%' THEN 'SteelSeries'
            WHEN sku LIKE 'KEY-CORSAIR-%' THEN 'Corsair'
            WHEN sku LIKE 'KEY-LOGITECH-%' THEN 'Logitech'
            WHEN sku LIKE 'KEY-HYPERX-%' THEN 'HyperX'
            WHEN sku LIKE 'KEY-GLORIOUS-%' THEN 'Glorious'
            WHEN sku LIKE 'KEY-ROCCAT-%' THEN 'Roccat'
            WHEN sku LIKE 'KEY-GENESIS-%' THEN 'Genesis'
            ELSE NULL
        END AS manufacturer,
        name
    FROM products
)
UPDATE products p
SET manufacturer = c.manufacturer,
    model = CASE
        WHEN p.sku LIKE 'SSD-WD-%' THEN substring(c.name FROM 4)
        WHEN c.name LIKE c.manufacturer || ' %' THEN substring(c.name FROM char_length(c.manufacturer) + 2)
        ELSE p.model
    END,
    updated_at = CURRENT_TIMESTAMP
FROM canonical c
WHERE p.id = c.id
  AND c.manufacturer IS NOT NULL;

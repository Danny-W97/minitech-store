-- Correct socket, chipset and form factor metadata for motherboard products.
UPDATE products
SET technical_parameters = CASE sku
    WHEN 'MBO-ASUS-TUF-GAMING-B650-PLUS-01' THEN '{"socket":"AM5","chipset":"B650","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-GIGABYTE-B650-AORUS-ELITE-AX-02' THEN '{"socket":"AM5","chipset":"B650","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-ASROCK-B650M-PRO-RS-03' THEN '{"socket":"AM5","chipset":"B650","formFactor":"mATX"}'::jsonb
    WHEN 'MBO-ASUS-PRIME-Z790-P-04' THEN '{"socket":"LGA1700","chipset":"Z790","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-GIGABYTE-Z790-GAMING-X-AX-05' THEN '{"socket":"LGA1700","chipset":"Z790","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-MSI-PRO-B760-P-WIFI-06' THEN '{"socket":"LGA1700","chipset":"B760","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-ASROCK-B550-STEEL-LEGEND-07' THEN '{"socket":"AM4","chipset":"B550","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-MSI-MAG-B650M-MORTAR-08' THEN '{"socket":"AM5","chipset":"B650","formFactor":"mATX"}'::jsonb
    WHEN 'MBO-ASUS-ROG-STRIX-X670E-A-09' THEN '{"socket":"AM5","chipset":"X670E","formFactor":"ATX"}'::jsonb
    WHEN 'MBO-GIGABYTE-X670-GAMING-X-AX-10' THEN '{"socket":"AM5","chipset":"X670","formFactor":"ATX"}'::jsonb
    ELSE technical_parameters
END,
updated_at = CURRENT_TIMESTAMP
WHERE sku LIKE 'MBO-%';

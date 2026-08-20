-- Correct manufacturer metadata for laptop search and manufacturer filtering.
-- V5 inserted these four records with shifted manufacturer values.
UPDATE products
SET manufacturer = 'ASUS',
    model = 'Vivobook S 15',
    updated_at = CURRENT_TIMESTAMP
WHERE sku = 'LAP-ASUS-VIVOBOOK-S-15-07';

UPDATE products
SET manufacturer = 'Lenovo',
    model = 'ThinkPad E14',
    updated_at = CURRENT_TIMESTAMP
WHERE sku = 'LAP-LENOVO-THINKPAD-E14-10';

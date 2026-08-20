UPDATE delivery_methods
SET free_delivery_from = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE code IN ('STANDARD_COURIER', 'PARCEL_LOCKER');

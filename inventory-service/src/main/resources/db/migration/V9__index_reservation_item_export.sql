CREATE INDEX idx_inventory_reservation_items_export_window
    ON inventory_reservation_items (reservation_id, id DESC);

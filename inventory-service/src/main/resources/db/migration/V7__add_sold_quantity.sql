ALTER TABLE inventory_items
    ADD COLUMN sold_quantity INTEGER NOT NULL DEFAULT 0
        CHECK (sold_quantity >= 0);

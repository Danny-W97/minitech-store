CREATE INDEX idx_promotion_redemptions_order_export_window
    ON promotion_redemptions (order_id, id DESC);

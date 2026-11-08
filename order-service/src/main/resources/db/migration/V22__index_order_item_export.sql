-- Bounded item pages for one verified owned order; no unbounded child aggregation.
CREATE INDEX idx_order_items_export_window ON order_items (order_id, id DESC);

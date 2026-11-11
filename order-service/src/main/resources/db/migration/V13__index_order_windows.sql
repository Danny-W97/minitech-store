CREATE INDEX idx_orders_customer_id_window ON orders (customer_id, id DESC);
CREATE INDEX idx_orders_status_window ON orders (status, id DESC);
CREATE INDEX idx_orders_customer_status_window ON orders (customer_id, status, id DESC);
CREATE INDEX idx_order_items_product_order ON order_items (product_id, order_id);

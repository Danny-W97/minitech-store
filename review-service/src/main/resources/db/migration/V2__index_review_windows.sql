CREATE INDEX idx_reviews_product_status_window ON reviews (product_id, status, id DESC) INCLUDE (rating);
CREATE INDEX idx_reviews_customer_window ON reviews (customer_id, id DESC);
CREATE INDEX idx_reviews_status_window ON reviews (status, id);

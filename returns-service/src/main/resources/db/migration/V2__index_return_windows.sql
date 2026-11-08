CREATE INDEX idx_returns_customer_window ON return_requests (customer_id, id DESC);
CREATE INDEX idx_returns_status_window ON return_requests (status, id DESC);

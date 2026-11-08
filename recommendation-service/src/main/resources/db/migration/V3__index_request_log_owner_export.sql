-- Keyset export of retained account-linked rows; retention behavior is unchanged.
CREATE INDEX idx_recommendation_requests_owner_export ON recommendation_requests (customer_id, id DESC);

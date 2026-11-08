-- Bounded keyset reads scoped to the verified customer, without sorting their entire wishlist.
CREATE INDEX idx_wishlist_export_owner_id ON wishlist_items (customer_id, id DESC);

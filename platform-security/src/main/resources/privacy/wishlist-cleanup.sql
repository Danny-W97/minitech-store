DELETE FROM wishlist_items WHERE id IN (SELECT id FROM wishlist_items WHERE customer_id=? ORDER BY id LIMIT 100)

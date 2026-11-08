DELETE FROM recommendation_requests WHERE id IN (SELECT id FROM recommendation_requests WHERE customer_id=? ORDER BY id LIMIT 100)

WITH batch AS MATERIALIZED (SELECT id,customer_id,order_id,product_id FROM reviews WHERE customer_id=? ORDER BY id LIMIT 100 FOR UPDATE),
            saved AS (INSERT INTO review_privacy_keys(customer_id,order_id,product_id) SELECT customer_id,order_id,product_id FROM batch ON CONFLICT DO NOTHING)
            DELETE FROM reviews r USING batch b WHERE r.id=b.id

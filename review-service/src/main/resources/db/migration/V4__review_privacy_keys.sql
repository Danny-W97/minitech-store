-- No review text, note, rating, dates or publication status are retained here.
CREATE TABLE review_privacy_keys (
 customer_id BIGINT NOT NULL CHECK(customer_id>0),
 order_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 PRIMARY KEY(customer_id,order_id,product_id)
);
CREATE FUNCTION privacy_review_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM review_privacy_keys WHERE customer_id=NEW.customer_id AND order_id=NEW.order_id AND product_id=NEW.product_id) THEN
  RAISE EXCEPTION 'Review privacy deduplication key retained' USING ERRCODE='23505';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER privacy_review_dedup BEFORE INSERT OR UPDATE OF customer_id,order_id,product_id ON reviews FOR EACH ROW EXECUTE FUNCTION privacy_review_key_guard();

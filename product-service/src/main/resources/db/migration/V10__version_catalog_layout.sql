-- Only membership/order changes invalidate the ID layout; prices stay uncached.
-- Hold off legacy writers until the initial count and its triggers are atomic.
LOCK TABLE products IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE catalog_layout (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL,
    product_count BIGINT NOT NULL CHECK (product_count >= 0)
);
INSERT INTO catalog_layout (id, revision, product_count) SELECT 1, 1, count(*) FROM products;

CREATE FUNCTION update_catalog_layout() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE changed_count BIGINT := 0;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT count(*) INTO changed_count FROM changed_products;
    ELSIF TG_OP = 'DELETE' THEN
        SELECT -count(*) INTO changed_count FROM changed_products;
    ELSIF TG_OP = 'TRUNCATE' THEN
        SELECT -product_count INTO changed_count FROM catalog_layout WHERE id = 1;
    END IF;
    UPDATE catalog_layout SET revision = revision + 1, product_count = product_count + changed_count WHERE id = 1;
    RETURN NULL;
END;
$$;

CREATE TRIGGER catalog_layout_insert AFTER INSERT ON products
REFERENCING NEW TABLE AS changed_products FOR EACH STATEMENT EXECUTE FUNCTION update_catalog_layout();
CREATE TRIGGER catalog_layout_delete AFTER DELETE ON products
REFERENCING OLD TABLE AS changed_products FOR EACH STATEMENT EXECUTE FUNCTION update_catalog_layout();
CREATE TRIGGER catalog_layout_truncate AFTER TRUNCATE ON products
FOR EACH STATEMENT EXECUTE FUNCTION update_catalog_layout();
CREATE TRIGGER catalog_layout_reorder AFTER UPDATE OF id ON products
FOR EACH STATEMENT EXECUTE FUNCTION update_catalog_layout();

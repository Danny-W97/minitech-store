-- Immutable command predecessor for targeted generation preparation only.
-- Historical/uncontrolled generations remain NULL; never infer lineage by time.
ALTER TABLE commerce_retention_jobs ADD COLUMN preparation_predecessor UUID;
ALTER TABLE commerce_retention_jobs ADD CONSTRAINT fk_retention_preparation_predecessor
 FOREIGN KEY(order_id,preparation_predecessor) REFERENCES commerce_retention_jobs(order_id,generation);
ALTER TABLE commerce_retention_jobs ADD CONSTRAINT ck_retention_preparation_predecessor
 CHECK(preparation_predecessor IS NULL OR preparation_predecessor<>generation);
CREATE FUNCTION retain_retention_preparation_predecessor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.preparation_predecessor IS DISTINCT FROM OLD.preparation_predecessor THEN
  RAISE EXCEPTION 'Retention preparation predecessor is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER retention_preparation_predecessor_guard BEFORE UPDATE ON commerce_retention_jobs
 FOR EACH ROW EXECUTE FUNCTION retain_retention_preparation_predecessor();

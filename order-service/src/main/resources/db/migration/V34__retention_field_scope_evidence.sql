-- Review provenance, not authorization to erase. Historical proofs are retained.
ALTER TABLE commerce_retention_evidence ADD COLUMN field_scope_hash VARCHAR(64);
ALTER TABLE commerce_retention_evidence ADD CONSTRAINT ck_retention_field_scope_hash
 CHECK(field_scope_hash IS NULL OR (kind='GUARD' AND source='FIELD_SCOPE_REVIEWED'
   AND state='CONFIRMED' AND field_scope_hash ~ '^[0-9a-f]{64}$'));
CREATE FUNCTION retain_retention_field_scope_hash() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.field_scope_hash IS NOT NULL THEN RAISE EXCEPTION 'Field scope receipt is retained' USING ERRCODE='23514'; END IF;
  RETURN OLD;
 END IF;
 IF NEW.field_scope_hash IS DISTINCT FROM OLD.field_scope_hash
   OR (OLD.field_scope_hash IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
  RAISE EXCEPTION 'Field scope receipt is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER retention_field_scope_hash_guard BEFORE UPDATE OR DELETE ON commerce_retention_evidence
 FOR EACH ROW EXECUTE FUNCTION retain_retention_field_scope_hash();

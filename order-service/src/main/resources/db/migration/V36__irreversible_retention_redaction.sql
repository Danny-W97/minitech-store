-- Irreversibility is independent of account deletion locks.
CREATE FUNCTION protect_redacted_order_state() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent BIGINT; redacted BOOLEAN;
BEGIN
 IF TG_TABLE_NAME='orders' THEN
  PERFORM pg_advisory_xact_lock_shared(435,hashint8(OLD.id));
  PERFORM pg_advisory_xact_lock(436,hashint8(OLD.id));
  IF OLD.privacy_redacted AND (TG_OP='DELETE' OR NOT NEW.privacy_redacted) THEN
   RAISE EXCEPTION 'Redaction cannot be reverted' USING ERRCODE='23514';
  END IF;
 ELSE
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  parent:=NEW.order_id;
  PERFORM pg_advisory_xact_lock_shared(435,hashint8(parent));
  PERFORM pg_advisory_xact_lock(436,hashint8(parent));
  SELECT privacy_redacted INTO redacted FROM orders WHERE id=parent;
  IF redacted AND ((TG_TABLE_NAME='order_items' AND to_jsonb(NEW)->>'product_name' IS NOT NULL)
       OR (TG_TABLE_NAME='order_sagas' AND to_jsonb(NEW)->>'last_error' IS NOT NULL)) THEN
   RAISE EXCEPTION 'Redacted content cannot be restored' USING ERRCODE='23514';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER a_irreversible_redaction BEFORE UPDATE OR DELETE ON orders FOR EACH ROW EXECUTE FUNCTION protect_redacted_order_state();
CREATE TRIGGER a_irreversible_redaction BEFORE INSERT OR UPDATE ON order_items FOR EACH ROW EXECUTE FUNCTION protect_redacted_order_state();
CREATE TRIGGER a_irreversible_redaction BEFORE INSERT OR UPDATE ON order_sagas FOR EACH ROW EXECUTE FUNCTION protect_redacted_order_state();

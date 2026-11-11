-- Minimal source replay markers. No TTL, contacts, authorization or runtime activation.
CREATE TABLE commerce_retention_fence_tokens (
 order_id BIGINT NOT NULL REFERENCES commerce_retention_source_state(order_id),
 binding_epoch BIGINT NOT NULL CHECK(binding_epoch>0),
 fence_token UUID NOT NULL,
 released BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(order_id,binding_epoch,fence_token)
);
CREATE FUNCTION protect_retention_fence_token() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_epoch BIGINT;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Fence replay markers are retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  PERFORM pg_advisory_xact_lock(436,hashint8(NEW.order_id));
  SELECT binding_epoch INTO current_epoch FROM commerce_retention_source_state WHERE order_id=NEW.order_id FOR UPDATE;
  IF NOT FOUND OR current_epoch<>NEW.binding_epoch
   OR (SELECT count(*) FROM commerce_retention_fence_tokens WHERE order_id=NEW.order_id AND binding_epoch=NEW.binding_epoch)>=8
   THEN RAISE EXCEPTION 'Invalid fence token scope or budget' USING ERRCODE='23514'; END IF;
 ELSE
  IF (NEW.order_id,NEW.binding_epoch,NEW.fence_token) IS DISTINCT FROM (OLD.order_id,OLD.binding_epoch,OLD.fence_token)
   OR OLD.released OR NOT NEW.released
   THEN RAISE EXCEPTION 'Fence replay marker is monotonic' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER retention_fence_token_protocol BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_fence_tokens
 FOR EACH ROW EXECUTE FUNCTION protect_retention_fence_token();

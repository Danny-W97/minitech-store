-- A43.33: durable ID-only fence. No email, email hash or automatic TTL.
CREATE TABLE account_privacy_fences (
 customer_id BIGINT PRIMARY KEY CHECK(customer_id>0),
 request_id UUID NOT NULL UNIQUE,
 state VARCHAR(12) NOT NULL CHECK(state='BLOCKED')
);

-- New account associations are blocked even for background/service writes.
CREATE FUNCTION privacy_customer_association_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE subject BIGINT;
BEGIN
 subject := NEW.customer_id;
 IF subject IS NULL THEN RETURN NEW; END IF;
 IF TG_OP='UPDATE' AND NEW.customer_id IS NOT DISTINCT FROM OLD.customer_id THEN RETURN NEW; END IF;
 PERFORM pg_advisory_xact_lock_shared(-subject);
 IF EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=subject) THEN
  RAISE EXCEPTION 'Account privacy fence active' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER privacy_new_customer_association BEFORE INSERT OR UPDATE OF customer_id ON orders FOR EACH ROW EXECUTE FUNCTION privacy_customer_association_guard();

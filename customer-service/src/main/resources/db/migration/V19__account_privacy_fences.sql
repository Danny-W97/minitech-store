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
CREATE TRIGGER privacy_new_customer_association BEFORE INSERT OR UPDATE OF customer_id ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION privacy_customer_association_guard();
CREATE TRIGGER privacy_new_customer_association BEFORE INSERT OR UPDATE OF customer_id ON password_reset_tokens FOR EACH ROW EXECUTE FUNCTION privacy_customer_association_guard();
CREATE TRIGGER privacy_new_customer_association BEFORE INSERT OR UPDATE OF customer_id ON customer_recovery_codes FOR EACH ROW EXECUTE FUNCTION privacy_customer_association_guard();
CREATE TRIGGER privacy_new_customer_association BEFORE INSERT OR UPDATE OF customer_id ON customer_totp_methods FOR EACH ROW EXECUTE FUNCTION privacy_customer_association_guard();

CREATE TABLE account_privacy_requests (
 id UUID PRIMARY KEY, customer_id BIGINT NOT NULL UNIQUE CHECK(customer_id>0),
 preview_key UUID NOT NULL, plan_hash VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, preview_expires_at TIMESTAMPTZ NOT NULL,
 confirmed_at TIMESTAMPTZ, phase VARCHAR(24) NOT NULL CHECK(phase IN ('PREVIEW','FENCING','NEEDS_ATTENTION','WAITING_ADAPTERS')),
 status_token_hash VARCHAR(64), status_expires_at TIMESTAMPTZ,
 run_token UUID, run_until TIMESTAMPTZ,
 CHECK((confirmed_at IS NULL AND phase='PREVIEW' AND status_token_hash IS NULL AND status_expires_at IS NULL)
    OR (confirmed_at IS NOT NULL AND phase<>'PREVIEW' AND status_expires_at=confirmed_at+INTERVAL '168 hours'))
);
CREATE INDEX idx_privacy_request_work ON account_privacy_requests(phase,confirmed_at,id);
CREATE TABLE account_privacy_services (
 request_id UUID NOT NULL REFERENCES account_privacy_requests(id), service VARCHAR(40) NOT NULL,
 fence_state VARCHAR(16) NOT NULL CHECK(fence_state IN ('PENDING','FENCED','FAILED')),
 data_action VARCHAR(32) NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),
 retry_at TIMESTAMPTZ NOT NULL, code VARCHAR(32),
 PRIMARY KEY(request_id,service)
);
-- Disablement is reversible only before a privacy request is confirmed; no old token can re-enable it.
CREATE FUNCTION privacy_customer_row_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock_shared(-NEW.id);
 IF EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=NEW.id) THEN
  IF TG_OP='INSERT' THEN RAISE EXCEPTION 'Account privacy fence active' USING ERRCODE='23514'; END IF;
  IF NEW.enabled OR (to_jsonb(NEW)-ARRAY['enabled','verification_token_hash','verification_expires_at','updated_at'])
     IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['enabled','verification_token_hash','verification_expires_at','updated_at']) THEN
   RAISE EXCEPTION 'Account privacy fence active' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER privacy_customer_row BEFORE INSERT OR UPDATE ON customers FOR EACH ROW EXECUTE FUNCTION privacy_customer_row_guard();

-- WebAuthn uses a canonical base64url-encoded decimal account ID, never an email match.
CREATE FUNCTION privacy_webauthn_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE raw_subject TEXT; subject BIGINT;
BEGIN
 BEGIN
  raw_subject := convert_from(decode(rpad(translate(NEW.user_entity_user_id,'-_','+/'),((length(NEW.user_entity_user_id)+3)/4)*4,'='),'base64'),'UTF8');
  IF raw_subject !~ '^[1-9][0-9]{0,18}$' OR raw_subject::numeric>9223372036854775807 THEN RETURN NEW; END IF;
  subject := raw_subject::bigint;
 EXCEPTION WHEN OTHERS THEN RETURN NEW;
 END;
 PERFORM pg_advisory_xact_lock_shared(-subject);
 IF EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=subject) THEN
  RAISE EXCEPTION 'Account privacy fence active' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER privacy_webauthn_association BEFORE INSERT OR UPDATE OF user_entity_user_id ON user_credentials FOR EACH ROW EXECUTE FUNCTION privacy_webauthn_guard();

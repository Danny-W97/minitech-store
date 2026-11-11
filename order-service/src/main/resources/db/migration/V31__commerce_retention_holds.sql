-- Known D02 purchase holds only; no complete coverage or preservation clearance.
CREATE TABLE commerce_retention_hold_scopes (
 scope_type VARCHAR(8) NOT NULL CHECK(scope_type IN ('ORDER','CUSTOMER')),
 subject_id BIGINT NOT NULL CHECK(subject_id>0),
 revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
 restore_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(scope_type,subject_id)
);
CREATE TABLE commerce_retention_holds (
 hold_id UUID PRIMARY KEY,
 scope_type VARCHAR(8) NOT NULL,
 subject_id BIGINT NOT NULL,
 guard VARCHAR(40) NOT NULL CHECK(guard IN ('NO_PRESERVATION_HOLD','NO_OPEN_OR_REOPENED_CASE','NO_ACTIVE_RETRY_OR_DLQ','FINANCIAL_DEPENDENCIES_RELEASED','RESTORE_DEPENDENCIES_RELEASED','RETRY_DEPENDENCIES_RELEASED','DEDUP_DEPENDENCIES_RELEASED')),
 reason_code VARCHAR(24) NOT NULL CHECK(reason_code IN ('PRESERVATION_REQUEST','OPEN_CASE','DEPENDENCY_REVIEW')),
 request_id UUID NOT NULL UNIQUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(scope_type,subject_id) REFERENCES commerce_retention_hold_scopes(scope_type,subject_id)
);
CREATE INDEX idx_retention_hold_lookup ON commerce_retention_holds(scope_type,subject_id,guard);
CREATE FUNCTION protect_retention_hold_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Retention hold history retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.revision<>0 THEN RAISE EXCEPTION 'No supplied hold revision' USING ERRCODE='23514'; END IF;
 ELSIF (NEW.scope_type,NEW.subject_id,NEW.created_at) IS DISTINCT FROM (OLD.scope_type,OLD.subject_id,OLD.created_at)
   OR (OLD.restore_blocked AND NOT NEW.restore_blocked)
   OR (pg_trigger_depth()<2 AND NEW.revision<>OLD.revision) THEN
  RAISE EXCEPTION 'Retention hold scope is immutable' USING ERRCODE='23514';
 END IF;RETURN NEW;
END; $$;
CREATE TRIGGER retention_hold_scope_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_scopes FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_scope();
CREATE FUNCTION advance_retention_hold_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE commerce_retention_hold_scopes SET revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE scope_type=NEW.scope_type AND subject_id=NEW.subject_id;
 RETURN NEW;
END; $$;
CREATE TRIGGER retention_hold_inserted AFTER INSERT ON commerce_retention_holds FOR EACH ROW EXECUTE FUNCTION advance_retention_hold_scope();
CREATE TRIGGER retention_hold_restore_changed AFTER UPDATE OF restore_blocked ON commerce_retention_hold_scopes
 FOR EACH ROW WHEN (NEW.restore_blocked AND NOT OLD.restore_blocked) EXECUTE FUNCTION advance_retention_hold_scope();
CREATE FUNCTION retain_retention_hold() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Retention hold is append only' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER retention_hold_immutable BEFORE UPDATE OR DELETE ON commerce_retention_holds FOR EACH ROW EXECUTE FUNCTION retain_retention_hold();
ALTER TABLE commerce_retention_jobs ADD COLUMN order_hold_revision BIGINT NOT NULL DEFAULT -1 CHECK(order_hold_revision>=-1);
ALTER TABLE commerce_retention_jobs ADD COLUMN customer_hold_revision BIGINT NOT NULL DEFAULT -1 CHECK(customer_hold_revision>=-1);
CREATE INDEX idx_retention_hold_claim ON commerce_retention_jobs(next_attempt_at,order_id)
 WHERE current AND state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW');

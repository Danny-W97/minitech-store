-- Supersession concerns read-only inspection probes only. It is not executor termination.
CREATE TABLE commerce_retention_hold_probe_resolutions (
 probe_id UUID PRIMARY KEY REFERENCES commerce_retention_hold_probes(probe_id),
 resolution_id UUID NOT NULL UNIQUE,
 replacement_probe_id UUID NOT NULL REFERENCES commerce_retention_hold_probe_results(probe_id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE commerce_retention_hold_repair_intents (
 intent_id UUID PRIMARY KEY,
 request_id UUID NOT NULL,
 source VARCHAR(20) NOT NULL,
 basis_probe_id UUID NOT NULL UNIQUE REFERENCES commerce_retention_hold_probe_results(probe_id),
 source_request_id UUID NOT NULL,
 source_hold_id UUID NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(request_id,source) REFERENCES commerce_retention_hold_delivery_receipts(request_id,source)
);
CREATE FUNCTION retention_hold_probe_supersedable(target UUID,replacement UUID) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(
  SELECT 1 FROM commerce_retention_hold_probes p
  JOIN commerce_retention_hold_probes n ON n.request_id=p.request_id AND n.source=p.source
   AND n.source_request_id=p.source_request_id AND n.source_hold_id=p.source_hold_id
  JOIN commerce_retention_hold_probe_results r ON r.probe_id=n.probe_id
  WHERE p.probe_id=target AND n.probe_id=replacement AND n.probe_no>p.probe_no
   AND n.probe_no=(SELECT max(probe_no) FROM commerce_retention_hold_probes WHERE request_id=p.request_id AND source=p.source)
   AND NOT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_results WHERE probe_id=target)
   AND NOT EXISTS(SELECT 1 FROM commerce_retention_hold_probe_resolutions WHERE probe_id=target)
   AND r.code IN ('MATCH_AS_OF_READ','MISSING_AT_SOURCE')
   AND r.observed_at BETWEEN clock_timestamp()-interval '15 seconds' AND clock_timestamp()+interval '5 seconds'
 );
$$;
CREATE FUNCTION retention_hold_repair_candidate(basis UUID) RETURNS BOOLEAN LANGUAGE SQL VOLATILE AS $$
 SELECT EXISTS(
  SELECT 1 FROM commerce_retention_hold_probes p JOIN commerce_retention_hold_probe_results r USING(probe_id)
  JOIN commerce_retention_hold_delivery_receipts d ON d.request_id=p.request_id AND d.source=p.source
   AND d.source_request_id=p.source_request_id AND d.source_hold_id=p.source_hold_id
  WHERE p.probe_id=basis AND r.code='MISSING_AT_SOURCE'
   AND r.observed_at BETWEEN clock_timestamp()-interval '15 seconds' AND clock_timestamp()+interval '5 seconds'
   AND p.probe_no=(SELECT max(probe_no) FROM commerce_retention_hold_probes WHERE request_id=p.request_id AND source=p.source)
   AND NOT EXISTS(SELECT 1 FROM commerce_retention_hold_probes x
     LEFT JOIN commerce_retention_hold_probe_results y USING(probe_id)
     LEFT JOIN commerce_retention_hold_probe_resolutions z USING(probe_id)
     WHERE x.request_id=p.request_id AND x.source=p.source AND y.probe_id IS NULL AND z.probe_id IS NULL)
 );
$$;
CREATE FUNCTION protect_retention_hold_recovery_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent UUID; subject BIGINT; blocked BOOLEAN;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Hold recovery plans are immutable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='commerce_retention_hold_probe_resolutions' THEN
  SELECT request_id INTO parent FROM commerce_retention_hold_probes WHERE probe_id=NEW.probe_id;
 ELSE parent:=NEW.request_id; END IF;
 SELECT order_id,restore_blocked INTO subject,blocked FROM commerce_retention_hold_requests WHERE request_id=parent FOR UPDATE;
 IF NOT FOUND OR blocked OR EXISTS(SELECT 1 FROM commerce_retention_hold_scopes h WHERE h.restore_blocked AND ((h.scope_type='ORDER' AND h.subject_id=subject) OR (h.scope_type='CUSTOMER' AND h.subject_id=(SELECT customer_id FROM orders WHERE id=subject)))) OR EXISTS(SELECT 1 FROM commerce_retention_order_state WHERE order_id=subject AND restore_blocked) THEN
  RAISE EXCEPTION 'Hold recovery scope quarantined or absent' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='commerce_retention_hold_probe_resolutions' THEN
  IF NOT retention_hold_probe_supersedable(NEW.probe_id,NEW.replacement_probe_id) THEN RAISE EXCEPTION 'Invalid hold probe supersession' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM commerce_retention_hold_probes p WHERE p.probe_id=NEW.basis_probe_id AND p.request_id=NEW.request_id AND p.source=NEW.source AND p.source_request_id=NEW.source_request_id AND p.source_hold_id=NEW.source_hold_id)
   OR NOT retention_hold_repair_candidate(NEW.basis_probe_id) THEN RAISE EXCEPTION 'Invalid hold repair intent' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_probe_resolution_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_probe_resolutions
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_recovery_plan();
CREATE TRIGGER retention_hold_repair_intent_guard BEFORE INSERT OR UPDATE OR DELETE ON commerce_retention_hold_repair_intents
 FOR EACH ROW EXECUTE FUNCTION protect_retention_hold_recovery_plan();
CREATE FUNCTION reject_superseded_hold_probe_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent UUID;
BEGIN
 SELECT request_id INTO parent FROM commerce_retention_hold_probes WHERE probe_id=NEW.probe_id;
 PERFORM 1 FROM commerce_retention_hold_requests WHERE request_id=parent FOR UPDATE;
 IF EXISTS(SELECT 1 FROM commerce_retention_hold_probe_resolutions WHERE probe_id=NEW.probe_id) THEN
  RAISE EXCEPTION 'Superseded read-only hold probe cannot append a late result' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_hold_probe_resolution_result_guard BEFORE INSERT ON commerce_retention_hold_probe_results
 FOR EACH ROW EXECUTE FUNCTION reject_superseded_hold_probe_result();

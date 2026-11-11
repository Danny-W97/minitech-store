#!/usr/bin/env python3
"""A43.37 privacy replay in resources owned by an isolated restore drill only.

Never accepts a source database/container and never releases an environment.
Core erasure reuses the same SQL resources as the application. Commercial and
financial processes resume their existing reviewed adapters in quarantine.
"""
import argparse,base64,hashlib,importlib.util,json,re,time,uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path);result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
recovery=module('privacy_recovery','scripts/backup/platform-recovery.py')
journal_module=module('privacy_journal','scripts/backup/privacy-journal.py')
SQL_ROOT=ROOT/'platform-security/src/main/resources/privacy'
MAX_BATCHES=10000

def policies():
    source=(ROOT/'platform-security/src/main/java/org/demo/platformsecurity/privacy/ErasurePolicies.java').read_text()
    values={}
    for name in ('core','commerce'):
        match=re.search(r'public static String '+name+r'Hash\(\)\{return org.demo.platformsecurity.Access.hash\("([^"\n]+)"\);\}',source)
        if not match:raise ValueError('Current erasure policy requires review')
        values[name]=hashlib.sha256(match[1].encode()).hexdigest()
    return values
def literal(value):
    if isinstance(value,int) and 0<value<=2**63-1:return str(value)
    if isinstance(value,str) and re.fullmatch('[A-Za-z0-9_+/=:.TZ-]{1,4096}',value):return "'"+value+"'"
    raise ValueError('Unsafe replay parameter')
def bind(sql,parameters):
    if sql.count('?')!=len(parameters):raise ValueError('Cleanup SQL binding changed')
    for value in parameters:sql=sql.replace('?',literal(value),1)
    return sql
def changed(restored,owner,sql):
    # DML command tags, not row content, are the only output of these fixed statements.
    raw=recovery.run('docker','exec',restored.postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',owner+'_db','-Atc',sql)
    match=re.search(r'(?:DELETE|UPDATE|INSERT 0) ([0-9]+)\s*$',raw)
    if not match:raise ValueError('Cleanup result could not be verified')
    return int(match[1])
def batches(restored,owner,sql,parameters):
    statement=bind(sql,parameters)
    for n in range(MAX_BATCHES):
        if changed(restored,owner,statement)==0:return n
    raise ValueError('Replay batch limit reached; keep quarantine and resume explicitly')
def receipt_literal(entry):return literal(journal_module.receipt(entry))

def require_owned_restore(restored):
    if not isinstance(restored,recovery.Restored) or restored.postgres not in restored.containers or restored.redis not in restored.containers:
        raise ValueError('Only owned isolated restore resources are supported')

def invalidate_commerce_assessments(restored):
    """Invalidate all restored D02 assessments, including healthy accounts and guests.

    Only fixed metadata statements in resources owned by this drill; keep every
    generation, receipt and business row. An old receipt is not current proof.
    """
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_jobs') IS NOT NULL)::int+(to_regclass('public.commerce_retention_evidence') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','invalidated':0,'releaseAllowed':False}
    if present!='2':raise ValueError('Incomplete commerce assessment schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN;
          SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (
            SELECT order_id,generation FROM commerce_retention_jobs WHERE current
            ORDER BY order_id,generation LIMIT 100 FOR UPDATE
          ), changed AS (
            UPDATE commerce_retention_jobs j SET current=FALSE,state='STALE',code='SOURCE_INVALIDATED',
              run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
            FROM batch b WHERE j.order_id=b.order_id AND j.generation=b.generation AND j.current
            RETURNING 1
          ) SELECT count(*) FROM changed;
          COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:
            raise ValueError('Commerce assessment invalidation result could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Commerce assessment replay limit reached; keep quarantine and resume explicitly')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_jobs WHERE current OR state='LEASED' OR run_token IS NOT NULL OR run_until IS NOT NULL")!='0':
        raise ValueError('Restored commerce assessment remains active; keep quarantine')
    return {'status':'INVALIDATED_IN_QUARANTINE','invalidated':total,'releaseAllowed':False}

def invalidate_observation_rounds(restored):
    """Preserve pending/acknowledged observation history; never resend source requests."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_observation_rounds') IS NOT NULL)::int+(to_regclass('public.commerce_retention_observation_leases') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','blocked':0,'releaseAllowed':False,'sourceRequestsSent':0}
    if present!='2':raise ValueError('Incomplete observation round schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (
            SELECT round_id FROM commerce_retention_observation_rounds WHERE NOT restore_blocked
            ORDER BY round_id LIMIT 100 FOR UPDATE
          ), changed AS (
            UPDATE commerce_retention_observation_rounds r SET restore_blocked=TRUE
            FROM batch b WHERE r.round_id=b.round_id AND NOT r.restore_blocked RETURNING 1
          ) SELECT count(*) FROM changed;
          COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:raise ValueError('Observation round quarantine could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Observation round replay limit reached; keep quarantine')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_observation_rounds WHERE NOT restore_blocked")!='0':raise ValueError('Restored observation round remains active; keep quarantine')
    return {'status':'BLOCKED_IN_QUARANTINE','blocked':total,'releaseAllowed':False,'sourceRequestsSent':0}

def invalidate_retention_intents(restored):
    """Quarantine preparation/stop metadata only; this is not executor termination."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_intents') IS NOT NULL)::int+(to_regclass('public.commerce_retention_intent_events') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','blocked':0,'releaseAllowed':False,'executionTerminated':False}
    if present!='2':raise ValueError('Incomplete retention intent schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (
            SELECT intent_id FROM commerce_retention_intents WHERE NOT restore_blocked
            ORDER BY intent_id LIMIT 100 FOR UPDATE
          ), changed AS (
            UPDATE commerce_retention_intents i SET state='QUARANTINED',restore_blocked=TRUE,
              revision=revision+1,last_request_id=gen_random_uuid(),updated_at=clock_timestamp()
            FROM batch b WHERE i.intent_id=b.intent_id AND NOT i.restore_blocked RETURNING 1
          ) SELECT count(*) FROM changed;
          COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:raise ValueError('Retention intent quarantine result could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Retention intent replay limit reached; keep quarantine')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_intents WHERE NOT restore_blocked OR state<>'QUARANTINED'")!='0':raise ValueError('Restored retention intent remains active; keep quarantine')
    return {'status':'BLOCKED_IN_QUARANTINE','blocked':total,'releaseAllowed':False,'executionTerminated':False}

def invalidate_hold_intake(restored):
    """Quarantine local intake while preserving its negative hold and immutable seven-source plan."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_requests') IS NOT NULL)::int+(to_regclass('public.commerce_retention_hold_source_requests') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','blocked':0,'releaseAllowed':False,'sourceDeliveryConfirmed':False}
    if present!='2':raise ValueError('Incomplete hold intake schema; keep quarantine')
    delivery=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_delivery_attempts') IS NOT NULL)::int+(to_regclass('public.commerce_retention_hold_delivery_receipts') IS NOT NULL)::int")
    if delivery not in ('0','2'):raise ValueError('Incomplete hold delivery history schema; keep quarantine')
    reconciliation=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_probes') IS NOT NULL)::int+(to_regclass('public.commerce_retention_hold_probe_results') IS NOT NULL)::int")
    if reconciliation not in ('0','2') or reconciliation=='2' and delivery!='2':raise ValueError('Incomplete hold reconciliation history schema; keep quarantine')
    planning=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_probe_resolutions') IS NOT NULL)::int+(to_regclass('public.commerce_retention_hold_repair_intents') IS NOT NULL)::int")
    if planning not in ('0','2') or planning=='2' and reconciliation!='2':raise ValueError('Incomplete hold recovery planning schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (SELECT request_id FROM commerce_retention_hold_requests WHERE NOT restore_blocked
            ORDER BY request_id LIMIT 100 FOR UPDATE), changed AS (
            UPDATE commerce_retention_hold_requests r SET restore_blocked=TRUE FROM batch b
            WHERE r.request_id=b.request_id AND NOT r.restore_blocked RETURNING 1
          ) SELECT count(*) FROM changed; COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:raise ValueError('Hold intake quarantine result could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Hold intake replay limit reached; keep quarantine')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_hold_requests r WHERE NOT r.restore_blocked OR (SELECT count(*) FROM commerce_retention_hold_source_requests s WHERE s.request_id=r.request_id)<>7")!='0':
        raise ValueError('Restored hold intake remains active or incomplete; keep quarantine')
    if delivery=='2' and restored.sql('order',"""
        WITH invalid_receipts AS (
          SELECT 1 FROM commerce_retention_hold_delivery_receipts r
          LEFT JOIN commerce_retention_hold_source_requests p ON p.request_id=r.request_id AND p.source=r.source AND p.source_request_id=r.source_request_id
          LEFT JOIN commerce_retention_hold_delivery_attempts a ON a.request_id=r.request_id AND a.source=r.source AND a.attempt_id=r.attempt_id
          WHERE p.request_id IS NULL OR a.attempt_id IS NULL
        ), invalid_attempts AS (
          SELECT 1 FROM commerce_retention_hold_delivery_attempts GROUP BY request_id,source
          HAVING min(attempt_no)<>1 OR max(attempt_no)<>count(*)
        ) SELECT (SELECT count(*) FROM invalid_receipts)+(SELECT count(*) FROM invalid_attempts);
    """)!='0':raise ValueError('Inconsistent hold delivery history; keep quarantine')
    if reconciliation=='2' and restored.sql('order',"""
        WITH invalid_probes AS (
          SELECT 1 FROM commerce_retention_hold_probes p
          LEFT JOIN commerce_retention_hold_delivery_receipts r ON r.request_id=p.request_id AND r.source=p.source AND r.source_request_id=p.source_request_id AND r.source_hold_id=p.source_hold_id
          WHERE r.request_id IS NULL
        ), invalid_probe_sequence AS (
          SELECT 1 FROM commerce_retention_hold_probes GROUP BY request_id,source
          HAVING min(probe_no)<>1 OR max(probe_no)<>count(*) OR count(*)>8
        ), orphan_results AS (
          SELECT 1 FROM commerce_retention_hold_probe_results r LEFT JOIN commerce_retention_hold_probes p USING(probe_id) WHERE p.probe_id IS NULL
        ) SELECT (SELECT count(*) FROM invalid_probes)+(SELECT count(*) FROM invalid_probe_sequence)+(SELECT count(*) FROM orphan_results);
    """)!='0':raise ValueError('Inconsistent hold reconciliation history; keep quarantine')
    if planning=='2' and restored.sql('order',"""
        WITH invalid_resolutions AS (
          SELECT 1 FROM commerce_retention_hold_probe_resolutions x
          LEFT JOIN commerce_retention_hold_probes p ON p.probe_id=x.probe_id
          LEFT JOIN commerce_retention_hold_probes n ON n.probe_id=x.replacement_probe_id AND n.request_id=p.request_id AND n.source=p.source AND n.source_request_id=p.source_request_id AND n.source_hold_id=p.source_hold_id AND n.probe_no>p.probe_no
          LEFT JOIN commerce_retention_hold_probe_results r ON r.probe_id=n.probe_id
          WHERE p.probe_id IS NULL OR n.probe_id IS NULL OR r.code IS NULL OR r.code NOT IN ('MATCH_AS_OF_READ','MISSING_AT_SOURCE')
           OR EXISTS(SELECT 1 FROM commerce_retention_hold_probe_results WHERE probe_id=x.probe_id)
        ), invalid_repair_intents AS (
          SELECT 1 FROM commerce_retention_hold_repair_intents i
          LEFT JOIN commerce_retention_hold_probes p ON p.probe_id=i.basis_probe_id AND p.request_id=i.request_id AND p.source=i.source AND p.source_request_id=i.source_request_id AND p.source_hold_id=i.source_hold_id
          LEFT JOIN commerce_retention_hold_probe_results r ON r.probe_id=p.probe_id
          WHERE p.probe_id IS NULL OR r.code IS NULL OR r.code<>'MISSING_AT_SOURCE'
        ) SELECT (SELECT count(*) FROM invalid_resolutions)+(SELECT count(*) FROM invalid_repair_intents);
    """)!='0':raise ValueError('Inconsistent hold recovery planning; keep quarantine')
    return {'status':'BLOCKED_IN_QUARANTINE','blocked':total,'releaseAllowed':False,'sourceDeliveryConfirmed':False,'deliveryHistoryVerified':delivery=='2','reconciliationHistoryVerified':reconciliation=='2','recoveryPlanningVerified':planning=='2','restorationDispatchVerified':verify_hold_restoration_dispatch(restored,planning),'reviewVersionsVerified':verify_hold_review_versions(restored),'reviewStepsVerified':verify_hold_review_steps(restored)}


def verify_hold_restoration_dispatch(restored,planning):
    """Preserve history under parent quarantine; never acknowledge pending or renew case authority."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_restoration_attempts') IS NOT NULL)::int+(to_regclass('public.commerce_retention_hold_restoration_results') IS NOT NULL)::int+(to_regclass('public.commerce_retention_hold_restoration_stops') IS NOT NULL)::int")
    if present=='0':return False
    if present!='3' or planning!='2':raise ValueError('Incomplete hold restoration dispatch schema; keep quarantine')
    if restored.sql('order',"""
        WITH invalid_dispatch_attempts AS (
          SELECT 1 FROM commerce_retention_hold_restoration_attempts a
          LEFT JOIN commerce_retention_hold_repair_intents i USING(intent_id)
          LEFT JOIN commerce_retention_hold_requests p USING(request_id)
          LEFT JOIN commerce_retention_hold_restoration_attempts f ON f.intent_id=a.intent_id AND f.attempt_no=1
          WHERE i.intent_id IS NULL OR p.request_id IS NULL OR NOT p.restore_blocked OR f.attempt_id IS NULL OR f.mode<>'RESTORE'
           OR a.attempt_no>1 AND (a.mode<>'REPLAY_ONLY' OR a.scope_hash<>f.scope_hash OR a.case_authorization_id<>f.case_authorization_id)
        ), invalid_dispatch_sequence AS (
          SELECT 1 FROM commerce_retention_hold_restoration_attempts GROUP BY intent_id HAVING min(attempt_no)<>1 OR max(attempt_no)<>count(*) OR count(*)>8
        ), invalid_dispatch_results AS (
          SELECT 1 FROM commerce_retention_hold_restoration_results r
          LEFT JOIN commerce_retention_hold_restoration_attempts a USING(attempt_id)
          LEFT JOIN commerce_retention_hold_repair_intents i USING(intent_id)
          LEFT JOIN commerce_retention_hold_probe_results b ON b.probe_id=i.basis_probe_id
          WHERE a.attempt_id IS NULL OR i.intent_id IS NULL OR b.probe_id IS NULL
           OR r.code='RESTORED' AND a.mode<>'RESTORE'
           OR r.code IN ('RESTORED','REPLAYED') AND (r.binding_epoch<>b.binding_epoch OR r.source_revision<=b.source_revision)
        ), invalid_dispatch_stops AS (
          SELECT 1 FROM commerce_retention_hold_restoration_stops s LEFT JOIN commerce_retention_hold_repair_intents i USING(intent_id) WHERE i.intent_id IS NULL
        ) SELECT (SELECT count(*) FROM invalid_dispatch_attempts)+(SELECT count(*) FROM invalid_dispatch_sequence)+(SELECT count(*) FROM invalid_dispatch_results)+(SELECT count(*) FROM invalid_dispatch_stops);
    """)!='0':raise ValueError('Inconsistent hold restoration dispatch history; keep quarantine')
    return True



def verify_hold_review_versions(restored):
    """Immutable diagnostic versions remain history under quarantine, never renewed validity."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_review_versions') IS NOT NULL)::int")
    if present=='0':return False
    if present!='1':raise ValueError('Unrecognized review version schema; keep quarantine')
    if restored.sql('order',"""
        WITH invalid_review_versions AS (
          SELECT 1 FROM commerce_retention_hold_review_versions v
          LEFT JOIN commerce_retention_hold_requests p USING(request_id)
          WHERE p.request_id IS NULL OR NOT p.restore_blocked OR v.protocol<>'HOLD_RESTORATION_REVIEW_V1'
           OR v.expires_at<=v.reviewed_at OR v.expires_at>v.reviewed_at+interval '15 seconds'
           OR v.version_hash<>encode(sha256(
             decode('04','hex')||int4send(octet_length(convert_to(v.protocol||'/version','UTF8')))||convert_to(v.protocol||'/version','UTF8')
             ||decode('04','hex')||int4send(64)||convert_to(v.scope_hash::text,'UTF8')
             ||decode('04','hex')||int4send(64)||convert_to(v.history_hash::text,'UTF8')
             ||decode('04','hex')||int4send(64)||convert_to(v.result_hash::text,'UTF8')),'hex')
        ), invalid_review_sequence AS (
          SELECT 1 FROM commerce_retention_hold_review_versions GROUP BY request_id
          HAVING min(ordinal)<>1 OR max(ordinal)<>count(*) OR count(*)>8
        ) SELECT (SELECT count(*) FROM invalid_review_versions)+(SELECT count(*) FROM invalid_review_sequence);
    """)!='0':raise ValueError('Inconsistent review version history; keep quarantine')
    return True


def verify_hold_review_steps(restored):
    """Retain diagnostic reservations under quarantine; never resume a possibly dispatched read."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_review_steps') IS NOT NULL)::int")
    if present=='0':return False
    if present!='1':raise ValueError('Unrecognized review step schema; keep quarantine')
    if restored.sql('order',"""
        WITH invalid_review_steps AS (
          SELECT 1 FROM commerce_retention_hold_review_steps s
          LEFT JOIN commerce_retention_hold_review_versions v USING(review_id)
          LEFT JOIN commerce_retention_hold_requests r ON r.request_id=v.request_id
          LEFT JOIN commerce_retention_hold_repair_intents i USING(intent_id)
          LEFT JOIN commerce_retention_hold_probes p ON p.probe_id=s.probe_id
          WHERE v.review_id IS NULL OR r.request_id IS NULL OR NOT r.restore_blocked OR i.intent_id IS NULL OR p.probe_id IS NULL
           OR s.step_id<>s.probe_id OR i.request_id<>v.request_id OR p.request_id<>v.request_id
           OR i.source<>s.source OR p.source<>s.source OR i.source_request_id<>p.source_request_id OR i.source_hold_id<>p.source_hold_id
           OR s.expires_at<>v.expires_at OR s.prepared_scope_hash<>v.scope_hash
           OR s.created_at<v.reviewed_at OR s.created_at>=s.expires_at OR p.created_at<v.reviewed_at OR p.created_at>=s.expires_at
        ) SELECT count(*) FROM invalid_review_steps;
    """)!='0':raise ValueError('Inconsistent review step history; keep quarantine')
    return True


def invalidate_retention_holds(restored):
    """Quarantine known purchase-hold scopes without changing their hold history."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_hold_scopes') IS NOT NULL)::int + (to_regclass('public.commerce_retention_holds') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','blocked':0,'releaseAllowed':False}
    if present!='2':raise ValueError('Incomplete retention hold schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (SELECT scope_type,subject_id FROM commerce_retention_hold_scopes WHERE NOT restore_blocked
            ORDER BY scope_type,subject_id LIMIT 100 FOR UPDATE), changed AS (
            UPDATE commerce_retention_hold_scopes h SET restore_blocked=TRUE,updated_at=CURRENT_TIMESTAMP
            FROM batch b WHERE h.scope_type=b.scope_type AND h.subject_id=b.subject_id RETURNING 1
          ) SELECT count(*) FROM changed; COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:raise ValueError('Retention hold quarantine result could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Retention hold replay limit reached; keep quarantine')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_hold_scopes WHERE NOT restore_blocked")!='0':
        raise ValueError('Restored retention hold scope remains active; keep quarantine')
    return {'status':'BLOCKED_IN_QUARANTINE','blocked':total,'releaseAllowed':False}


def invalidate_commerce_order_state(restored):
    """Keep the observed local clock for history but quarantine its use."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_order_state') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','blocked':0,'releaseAllowed':False}
    if present!='1':raise ValueError('Unrecognized Order milestone schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (SELECT order_id FROM commerce_retention_order_state WHERE NOT restore_blocked
            ORDER BY order_id LIMIT 100 FOR UPDATE), changed AS (
            UPDATE commerce_retention_order_state t SET restore_blocked=TRUE,updated_at=CURRENT_TIMESTAMP
            FROM batch b WHERE t.order_id=b.order_id RETURNING 1
          ) SELECT count(*) FROM changed; COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:raise ValueError('Order milestone quarantine result could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Order milestone replay limit reached; keep quarantine')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_order_state WHERE NOT restore_blocked")!='0':
        raise ValueError('Restored Order milestone remains active; keep quarantine')
    return {'status':'BLOCKED_IN_QUARANTINE','blocked':total,'releaseAllowed':False}

def invalidate_commerce_bindings(restored):
    """Quarantine retained coordinator commands, including completed receipts."""
    require_owned_restore(restored)
    present=restored.sql('order',"SELECT (to_regclass('public.commerce_retention_bindings') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','blocked':0,'releaseAllowed':False}
    if present!='1':raise ValueError('Unrecognized commerce binding schema; keep quarantine')
    total=0
    for _ in range(MAX_BATCHES):
        count=restored.sql('order',"""
          BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
          WITH batch AS (SELECT order_id,generation,source FROM commerce_retention_bindings WHERE NOT restore_blocked
            ORDER BY order_id,generation,source LIMIT 100 FOR UPDATE), changed AS (
            UPDATE commerce_retention_bindings t SET restore_blocked=TRUE,
              state=CASE WHEN state='LEASED' THEN CASE WHEN attempts=8 THEN 'FAILED' ELSE 'PENDING' END ELSE state END,
              code=CASE WHEN state='LEASED' THEN CASE WHEN attempts=8 THEN 'RETRY_LIMIT' ELSE NULL END ELSE code END,
              run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
            FROM batch b WHERE t.order_id=b.order_id AND t.generation=b.generation AND t.source=b.source RETURNING 1
          ) SELECT count(*) FROM changed; COMMIT;
        """)
        if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:raise ValueError('Commerce binding quarantine result could not be verified')
        total+=int(count)
        if count=='0':break
    else:raise ValueError('Commerce binding replay limit reached; keep quarantine')
    if restored.sql('order',"SELECT count(*) FROM commerce_retention_bindings WHERE NOT restore_blocked OR state='LEASED' OR run_token IS NOT NULL OR run_until IS NOT NULL")!='0':
        raise ValueError('Restored commerce binding remains active; keep quarantine')
    return {'status':'BLOCKED_IN_QUARANTINE','blocked':total,'releaseAllowed':False}

COMMERCE_SOURCE_ALIASES=('returns','inventory','promotion','delivery','payment','billing','notification')

def invalidate_commerce_sources(restored):
    """Visit every implemented source, independently of the journal entries."""
    require_owned_restore(restored)
    sources={alias:invalidate_commerce_source(restored,alias) for alias in COMMERCE_SOURCE_ALIASES}
    return {'status':'ABSENT_HISTORICAL_SCHEMA' if all(s['status']=='ABSENT_HISTORICAL_SCHEMA' for s in sources.values()) else 'BLOCKED_IN_QUARANTINE',
            'bindingsBlocked':sum(s['bindingsBlocked'] for s in sources.values()),
            'leasesRetired':sum(s['leasesRetired'] for s in sources.values()),'sources':sources,'releaseAllowed':False}

def invalidate_commerce_source(restored,alias):
    """Retire one implemented source's bindings and leases, never clear a hold.

    The durable restore flag prevents even a delayed binding command from
    clearing quarantine. There is deliberately no release API in this stage.
    """
    require_owned_restore(restored)
    if alias not in COMMERCE_SOURCE_ALIASES:raise ValueError('Unreviewed commerce source')
    present=restored.sql(alias,"SELECT (to_regclass('public.commerce_retention_source_state') IS NOT NULL)::int+(to_regclass('public.commerce_retention_change_outbox') IS NOT NULL)::int")
    if present=='0':return {'status':'ABSENT_HISTORICAL_SCHEMA','bindingsBlocked':0,'leasesRetired':0,'releaseAllowed':False}
    if present!='2':raise ValueError('Incomplete commerce source schema; keep quarantine')
    totals=[]
    for table,predicate,assignment in (
        ('commerce_retention_source_state','NOT restore_blocked',
         'restore_blocked=TRUE,invalidated=TRUE,binding_epoch=binding_epoch+1,source_revision=source_revision+1'),
        ('commerce_retention_change_outbox',"state='LEASED'",
         "state=CASE WHEN attempts=8 THEN 'FAILED' ELSE 'PENDING' END,code=CASE WHEN attempts=8 THEN 'RETRY_LIMIT' ELSE NULL END,run_token=NULL,run_until=NULL"),
    ):
        # Fixed internal statement fragments only; never caller-controlled SQL.
        key='order_id,binding_epoch' if table.endswith('outbox') else 'order_id'
        join='t.order_id=b.order_id'+(' AND t.binding_epoch=b.binding_epoch' if table.endswith('outbox') else '')
        total=0
        for _ in range(MAX_BATCHES):
            count=restored.sql(alias,f"""
              BEGIN; SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='5s';
              WITH batch AS (SELECT {key} FROM {table} WHERE {predicate} ORDER BY {key} LIMIT 100 FOR UPDATE),
              changed AS (UPDATE {table} t SET {assignment},updated_at=CURRENT_TIMESTAMP
                FROM batch b WHERE {join} RETURNING 1)
              SELECT count(*) FROM changed; COMMIT;
            """)
            if not re.fullmatch(r'0|[1-9][0-9]{0,2}',count) or int(count)>100:
                raise ValueError('Commerce source invalidation result could not be verified')
            total+=int(count)
            if count=='0':break
        else:raise ValueError('Commerce source replay limit reached; keep quarantine')
        totals.append(total)
    if restored.sql(alias,"SELECT (SELECT count(*) FROM commerce_retention_source_state WHERE NOT restore_blocked OR NOT invalidated)+(SELECT count(*) FROM commerce_retention_change_outbox WHERE state='LEASED' OR run_token IS NOT NULL OR run_until IS NOT NULL)")!='0':
        raise ValueError('Restored commerce source remains active; keep quarantine')
    restoration=verify_hold_restoration_receipts(restored,alias)
    return {'status':'BLOCKED_IN_QUARANTINE','bindingsBlocked':totals[0],'leasesRetired':totals[1],'releaseAllowed':False,'restorationReceiptsVerified':restoration}

def verify_hold_restoration_receipts(restored,alias):
    require_owned_restore(restored)
    if alias not in COMMERCE_SOURCE_ALIASES:raise ValueError('Unreviewed restoration receipt source')
    present=restored.sql(alias,"SELECT to_regclass('public.commerce_retention_hold_restoration_receipts') IS NOT NULL")
    if present=='f':return False
    if present!='t':raise ValueError('Unverified restoration receipt schema; keep quarantine')
    if restored.sql(alias,"""SELECT count(*) FROM commerce_retention_hold_restoration_receipts r
      LEFT JOIN commerce_retention_source_state s ON s.order_id=r.order_id
      LEFT JOIN commerce_retention_source_holds h ON h.hold_id=r.hold_id AND h.order_id=r.order_id AND h.request_id=r.source_request_id
      WHERE s.order_id IS NULL OR NOT s.restore_blocked OR h.hold_id IS NULL""")!='0':raise ValueError('Inconsistent restoration receipts; keep quarantine')
    return True

def replay(restored,entries):
    require_owned_restore(restored)
    # Every alias must support the durable fence protocol before the first replay.
    for alias in recovery.aliases():
        if restored.sql(alias,"SELECT to_regclass('public.account_privacy_fences') IS NOT NULL")!='t':raise ValueError('Historical schema needs isolated migration and another drill')
    if restored.sql('customer',"SELECT to_regclass('public.restore_security_state') IS NOT NULL")!='t':raise ValueError('Restore session barrier schema missing')
    # Independent of the erasure journal's entries: no restored positive D02
    # attestation may survive as current for another account or a guest order.
    commerce_assessments=invalidate_commerce_assessments(restored)
    commerce_observations=invalidate_observation_rounds(restored)
    commerce_intents=invalidate_retention_intents(restored)
    commerce_hold_intake=invalidate_hold_intake(restored)
    commerce_holds=invalidate_retention_holds(restored)
    commerce_order_state=invalidate_commerce_order_state(restored)
    commerce_bindings=invalidate_commerce_bindings(restored)
    commerce_sources=invalidate_commerce_sources(restored)
    epoch=str(uuid.uuid4())
    restored.sql('customer',"UPDATE restore_security_state SET auth_epoch="+literal(epoch)+",restored_at=now(),phase='QUARANTINED' WHERE id=1; UPDATE refresh_tokens SET revoked_at=coalesce(revoked_at,now()); DELETE FROM password_reset_tokens;")
    if restored.sql('customer',"SELECT count(*) FROM restore_security_state WHERE id=1 AND phase='QUARANTINED'")!='1':raise ValueError('Restore login barrier not installed')
    # Only explicit temporary authentication prefixes; stream/group/PEL/dedup/rate keys stay intact.
    removed=0
    for pattern in ('customer:mfa:login:*','customer:mfa:enrollment:*','customer:mfa:webauthn:login:*','customer:mfa:webauthn:registration:*'):
        cursor='0'
        for n in range(MAX_BATCHES):
            cursor,keys=restored.redis_json('SCAN',cursor,'MATCH',pattern,'COUNT',100)
            if keys:removed+=int(restored.redis_json('DEL',*keys))
            if str(cursor)=='0':break
        else:raise ValueError('Authentication replay limit reached')
        _,remaining=restored.redis_json('SCAN','0','MATCH',pattern,'COUNT',100)
        if remaining:raise ValueError('Restored authentication challenges remain')
    root_source=(ROOT/'customer-service/src/main/java/org/demo/customerservice/privacy/PrivacyPlan.java').read_text()
    scopes=re.findall(r'new Scope\("([a-z-]+)","([A-Z0-9_]+)","[A-Za-z0-9./]+"\)',root_source)
    if {name.removesuffix('-service') for name,action in scopes}!=set(recovery.aliases()):raise ValueError('Restore service inventory changed')
    reviewed=policies();completed=0;pending=0
    for entry in entries:
        capsule=entry['capsule'];customer=int(capsule['customerId']);request=capsule['requestId'];cid=str(customer);rid=literal(request)
        # Replay identifiers, never email addresses or token hashes. Existing different identities stop the drill.
        for alias in recovery.aliases():
            restored.sql(alias,f"INSERT INTO account_privacy_fences(customer_id,request_id,state) VALUES({cid},{rid},'BLOCKED') ON CONFLICT(customer_id) DO NOTHING")
            if restored.sql(alias,f"SELECT request_id FROM account_privacy_fences WHERE customer_id={cid}")!=request:raise ValueError('Restore privacy identity conflict')
        existing=restored.sql('customer',f"SELECT id FROM account_privacy_requests WHERE customer_id={cid}")
        if existing and existing!=request:raise ValueError('Restore request identity conflict')
        values=[literal(capsule[name]) if capsule[name] is not None else 'NULL' for name in ('accountPolicyHash','commercePolicyHash','financePolicyHash')]
        proof=receipt_literal(entry);confirmed=literal(capsule['confirmedAt']);plan=literal(journal_module.capsule_hash(capsule))
        restored.sql('customer',f"""
          INSERT INTO account_privacy_requests(id,customer_id,preview_key,plan_hash,created_at,preview_expires_at,confirmed_at,phase,account_cleanup_policy_hash,commerce_cleanup_policy_hash,finance_policy_hash,journal_receipt,restore_imported)
          VALUES({rid},{cid},{rid},{plan},{confirmed},{confirmed},{confirmed},'WAITING_ADAPTERS',{','.join(values)},{proof},TRUE) ON CONFLICT(id) DO NOTHING;
          UPDATE account_privacy_requests SET restore_imported=TRUE,status_token_hash=NULL,status_expires_at=NULL,run_token=NULL,run_until=NULL,phase='WAITING_ADAPTERS',journal_receipt={proof},
           account_cleanup_policy_hash={values[0]},commerce_cleanup_policy_hash={values[1]},finance_policy_hash={values[2]},
           commerce_cleanup_pending=({values[1]} IS NOT NULL),finance_pending=({values[2]} IS NOT NULL),finance_state='PENDING',finance_attempts=0,finance_retry_at=now(),finance_orders=0,finance_prepared_orders=0 WHERE id={rid};
          UPDATE customers SET enabled=FALSE,verification_token_hash=NULL,verification_expires_at=NULL WHERE id={cid};
        """)
        for service,action in scopes:
            restored.sql('customer',f"INSERT INTO account_privacy_services(request_id,service,fence_state,data_action,retry_at) VALUES({rid},'{service}','FENCED','{action}',now()) ON CONFLICT(request_id,service) DO UPDATE SET fence_state='FENCED',attempts=0,code='FENCE_ACKNOWLEDGED'")
        # A previously restored READY job is not fresh reconciliation evidence. Keep its dedup receipts.
        restored.sql('order',f"UPDATE finance_privacy_jobs SET state='PENDING',step=0,cursor='0-0',attempts=0,retry_at=now(),run_token=NULL,run_until=NULL,code='RESTORE_RECONCILIATION_REQUIRED' WHERE customer_id={cid}; UPDATE finance_privacy_consents SET journal_receipt={proof},scan_after=0 WHERE customer_id={cid} AND request_id={rid}")
        restored.sql('customer',f"UPDATE account_privacy_services SET cleanup_state='PENDING',cleanup_cursor=0,cleanup_attempts=0,cleanup_retry_at=now(),cleanup_code='RESTORE_RECONCILIATION_REQUIRED' WHERE request_id={rid} AND service IN ('order-service','inventory-service','promotion-service','delivery-service','returns-service','payment-service','billing-service','notification-service')")
        if capsule['accountPolicyHash']!=reviewed['core']:
            pending+=1;continue
        for alias in ('wishlist','recommendation','review'):
            batches(restored,alias,(SQL_ROOT/(alias+'-cleanup.sql')).read_text(),[customer])
            restored.sql('customer',f"UPDATE account_privacy_services SET cleanup_state='DONE',cleanup_code='{ 'REVIEW_KEYS_RETAINED' if alias=='review' else 'PRIVATE_ROWS_REMOVED' }' WHERE request_id={rid} AND service='{alias}-service'")
        raw=cid.encode();standard=base64.b64encode(raw).decode();url=base64.urlsafe_b64encode(raw).decode();parameters={'customer':customer,'standard':standard,'standardUnpadded':standard.rstrip('='),'url':url,'urlUnpadded':url.rstrip('=')}
        for step in json.loads((SQL_ROOT/'customer-cleanup.json').read_text()):
            values=[parameters[name] for name in step['parameters']]
            if step['terminal']:changed(restored,'customer',bind(step['sql'],values))
            else:batches(restored,'customer',step['sql'],values)
        if restored.sql('customer',f"SELECT count(*) FROM customers WHERE id={cid}")!='0':raise ValueError('Restored profile remains')
        restored.sql('customer',f"UPDATE account_privacy_services SET cleanup_state='DONE',cleanup_code='CORE_REMOVED_AUTH_TTL' WHERE request_id={rid} AND service='customer-service'; UPDATE account_privacy_requests SET account_cleanup_pending=TRUE WHERE id={rid}")
        completed+=1
    # Keep sequences above all protected identifiers, including accounts absent from an older snapshot.
    if entries:
        maximum=max(int(entry['capsule']['customerId']) for entry in entries)
        restored.sql('customer',f"SELECT setval(pg_get_serial_sequence('customers','id'),greatest((SELECT coalesce(max(id),1) FROM customers),{maximum},(SELECT last_value FROM customers_id_seq)),true)")
    return {'status':'REAPPLIED_IN_QUARANTINE','requests':len(entries),'coreReplayed':completed,'unknownCorePolicies':pending,'authenticationChallengesRemoved':removed,'sessionsInvalidated':True,'persistentMfaPreservedForOtherAccounts':True,'commerceAssessments':commerce_assessments,'commerceObservationRounds':commerce_observations,'commerceIntents':commerce_intents,'commerceHoldIntake':commerce_hold_intake,'commerceHolds':commerce_holds,'commerceOrderState':commerce_order_state,'commerceBindings':commerce_bindings,'commerceSources':commerce_sources,'commercialReconciliationRequired':True,'releaseAllowed':False,'fullErasureComplete':False}

def drill(bundle,identity,journal,expected_head,report_path):
    report={'stage':'A43.37','status':'BLOCKED','releaseAllowed':False,'fullA36Accepted':False,'fullA43Complete':False};started=time.monotonic();report_path=journal_module.path_safe(report_path)
    if report_path.exists():raise ValueError('A new report file is required')
    for protected in (journal.directory,journal.backup_root):
        if report_path.is_relative_to(protected.resolve()):raise ValueError('Report must stay outside protected journal and backup storage')
    try:
        if not expected_head:raise ValueError('Current independently supplied journal head required')
        head,entries=journal.load(expected_head)
        # Journal/key containment was checked before any restored resources can exist.
        with recovery.Restored(bundle,identity) as restored:
            public_der=journal_module.command('openssl','pkey','-pubin','-in',str(journal.public_key),'-outform','DER')
            binding=restored.sql('customer',"SELECT coalesce(ledger_id::text,'')||'|'||coalesce(verification_key_hash,'') FROM restore_security_state WHERE id=1")
            if binding!=head['ledgerId']+'|'+hashlib.sha256(public_der).hexdigest():raise ValueError('Backup belongs to a different or unbound privacy journal')
            report['replay']=replay(restored,entries);report['journalSequence']=head['sequence'];report['status']='PASS';report['privacyStatus']='CORE_REAPPLIED_BUSINESS_RECONCILIATION_REQUIRED';report['isolatedRunId']=restored.run_id
        report['cleanupComplete']=True
    except Exception:
        report['status']='BLOCKED';raise
    finally:
        recovery.private_directory(report_path.parent)
        if report_path.is_symlink():raise ValueError('Report symlink forbidden')
        with report_path.open('x') as output:report_path.chmod(0o600);output.write(json.dumps(report,indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('bundle','identity','journal-directory','journal-identity','journal-public-key','backup-root','report'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--expected-head',required=True);a=p.parse_args();journal=journal_module.Journal(a.journal_directory,a.journal_identity,a.journal_public_key,a.backup_root)
    drill(a.bundle,a.identity,journal,a.expected_head,a.report);print('PASS privacy replay in isolated quarantine; release remains blocked')

if __name__=='__main__':
    try:main()
    except Exception as failure:raise SystemExit('Privacy restore blocked: '+type(failure).__name__)

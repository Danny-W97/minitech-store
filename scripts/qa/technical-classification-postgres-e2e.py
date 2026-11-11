#!/usr/bin/env python3
"""A43.30: compare parsed fields to isolated real PostgreSQL migrations and dedupe constraints."""
import base64,importlib.util,json,re,secrets,subprocess,time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('classification',Path(__file__).with_name('technical-data-classification-check.py'));gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def command(*args,input=None):
    result=subprocess.run(args,cwd=ROOT,input=input,capture_output=True,text=True)
    if result.returncode:raise RuntimeError('Fixture command failed: '+args[0]+'; '+result.stderr[-1200:])
    return result.stdout.strip()

def main():
    result=gate.check(ROOT);contract=gate.lifecycle.document(gate.lifecycle.source(ROOT,gate.CONTRACT));expected=gate.schema(ROOT)
    directory=ROOT/'tmp'/('classification-postgres-'+secrets.token_hex(4));directory.mkdir(parents=True);postgres=None;checks=[];migrations=0;obligations=0;expression_obligations=0;privacy_fences=0;privacy_associations=0
    try:
        postgres=command('docker','run','-d','--rm','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+secrets.token_urlsafe(32),'postgres:16-alpine')
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','fixture'],capture_output=True).returncode==0:break
            time.sleep(.3)
        else:raise RuntimeError('Fixture PostgreSQL startup timed out')
        owners=sorted({name.split('/')[0] for name in expected})
        for index,owner in enumerate(owners):
            database='fixture_'+str(index);command('docker','exec',postgres,'createdb','-U','fixture',database)
            paths=sorted((ROOT/owner/'src/main/resources/db/migration').glob('V*.sql'),key=lambda p:tuple(map(int,re.match(r'V([\d_]+)__',p.name)[1].split('_'))));migrations+=len(paths)
            command('docker','exec','-i',postgres,'psql','-X','-q','-v','ON_ERROR_STOP=1','-U','fixture','-d',database,
                    input='BEGIN;\n'+'\n'.join(p.read_text(encoding='utf-8') for p in paths)+'\nCOMMIT;\n')
            def sql(query):return command('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d',database,'-Atqc',query)
            columns=json.loads(sql("SELECT json_object_agg(table_name,columns) FROM (SELECT table_name,json_agg(column_name ORDER BY column_name) AS columns FROM information_schema.columns WHERE table_schema='public' GROUP BY table_name) q"))
            owned={key.split('/')[1]:value for key,value in expected.items() if key.startswith(owner+'/')};assert columns==owned,(owner,'DDL parser differs from real PostgreSQL')
            indexes=json.loads(sql("""
                SELECT coalesce(json_agg(q),'[]'::json) FROM (
                  SELECT t.relname AS table_name,array_agg(a.attname ORDER BY k.position) AS columns
                  FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                  JOIN unnest(i.indkey) WITH ORDINALITY k(attnum,position) ON true
                  JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum
                  WHERE n.nspname='public' AND i.indisunique AND i.indisvalid AND i.indpred IS NULL
                  GROUP BY t.relname,i.indexrelid) q
                """))
            for table,keys in contract['deduplicationConstraints'].items():
                if not table.startswith(owner+'/'):continue
                short=table.split('/')[1];available={tuple(row['columns']) for row in indexes if row['table_name']==short}
                for key in keys:assert tuple(key) in available,(table,'deduplication constraint missing',key);obligations+=1
            for table,expression in contract.get('deduplicationExpressions',{}).items():
                if not table.startswith(owner+'/'):continue
                short=table.split('/')[1];name=expression['name']
                assert sql(f"SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_class t ON t.oid=i.indrelid WHERE c.relname='{name}' AND t.relname='{short}' AND i.indisunique AND i.indisvalid AND i.indexprs IS NOT NULL")=='1'
                expression_obligations+=1
                import hashlib
                value='¯ó³æ@example.test'
                assert sql("SELECT privacy_identity_hash('"+value+"')")==hashlib.sha256(value.encode('utf-8')).hexdigest()
            if owner=='payment-service':
                key='PAYMENT_SUCCEEDED:900000001:oracle@fixture.example'
                sql(f"INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,attempts,next_attempt_at,created_at,updated_at) VALUES('{key}','PAYMENT_SUCCEEDED',900000001,1,'oracle@fixture.example','PENDING',0,now(),now(),now())")
                sql("DO $$ BEGIN INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,attempts,next_attempt_at,created_at,updated_at,identity_version,stored_event_id) VALUES('v2:'||privacy_identity_hash('"+key+"'),'PAYMENT_SUCCEEDED',900000001,1,'oracle@fixture.example','PENDING',0,now(),now(),now(),2,'payment-v2-12345678-1234-4234-8234-123456789012'); RAISE EXCEPTION 'Duplicate identity accepted'; EXCEPTION WHEN unique_violation THEN NULL; END $$;")
            if owner=='notification-service':
                sql("INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,attempt_count,created_at,updated_at,sent_at) VALUES('PAYMENT_SUCCEEDED',900000001,'oracle@fixture.example','EMAIL','Fixture','Fixture text','SENT',1,now(),now(),now())")
                sql("UPDATE notifications SET recipient_hash=privacy_identity_hash(recipient),recipient=NULL,subject=NULL,message=NULL,privacy_redacted=TRUE,content_compacted_at=now() WHERE source_id=900000001")
                sql("DO $$ BEGIN INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,attempt_count,created_at,updated_at) VALUES('PAYMENT_SUCCEEDED',900000001,'oracle@fixture.example','EMAIL','Fixture','Fixture text','PENDING',0,now(),now()); RAISE EXCEPTION 'Retired identity accepted'; EXCEPTION WHEN unique_violation THEN NULL; END $$;")
            if owner=='chat-service':assert sql("SELECT count(*) FROM pg_trigger WHERE tgname='chat_entries_immutable' AND tgenabled='O'")=='1'
            if 'account_privacy_fences' in owned:
                assert owned['account_privacy_fences']==['customer_id','request_id','state']
                subject=9000000001;request=secrets.token_hex(16)
                if owner=='customer-service':
                    sql(f"INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at) VALUES({subject},'oracle@fixture.example','fixture-only','Jan','Fixture','Test','00-001','Test','PL',false,now(),now())")
                sql(f"INSERT INTO account_privacy_fences(customer_id,request_id,state) VALUES({subject},'{request}','BLOCKED')")
                privacy_fences+=1
                associations={'customer-service':['refresh_tokens','password_reset_tokens','customer_totp_methods','customer_recovery_codes'],
                    'order-service':['orders'],'wishlist-service':['wishlist_items'],'review-service':['reviews'],
                    'recommendation-service':['recommendation_requests'],'returns-service':['return_requests']}.get(owner,[])
                statements=[f'INSERT INTO {table}(customer_id) VALUES({subject})' for table in associations]
                if owner=='customer-service':
                    encoded=base64.urlsafe_b64encode(str(subject).encode()).decode().rstrip('=')
                    statements += [f"UPDATE customers SET enabled=true WHERE id={subject}",f"INSERT INTO user_credentials(user_entity_user_id) VALUES('{encoded}')"]
                for statement in statements:
                    sql("DO $$ BEGIN "+statement+"; RAISE EXCEPTION 'Missing privacy guard'; EXCEPTION WHEN check_violation THEN IF SQLERRM<>'Account privacy fence active' THEN RAISE; END IF; END $$;")
                    privacy_associations+=1
            checks.append({'owner':owner,'tables':len(owned),'columns':sum(map(len,owned.values())),'status':'PASS'})
        assert migrations==result['migrationsReviewed']
        report={'stage':'A43.30','status':'PASS','checks':checks,'databaseOwners':len(checks),'tables':result['tables'],'columns':result['columns'],'migrations':migrations,'uniqueKeyObligations':obligations,'expressionUniqueObligations':expression_obligations,
                'privacyFenceDatabases':privacy_fences,'privacyAssociationGuards':privacy_associations,
                'source':'source-migrations-plus-explicit-isolated-privacy-fixtures','realUserDataAccessed':False,'identityMigrationImplemented':result['identityMigrationImplemented'],'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','databaseOwners':len(checks),'columns':result['columns'],'uniqueKeyObligations':obligations,'expressionUniqueObligations':expression_obligations,'report':str(directory/'report.json')}))
    finally:
        if postgres:command('docker','rm','-f',postgres)

if __name__=='__main__':main()

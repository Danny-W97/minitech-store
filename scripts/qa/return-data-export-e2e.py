#!/usr/bin/env python3
"""A43.23: bounded owner-only return case, item and safe event exports against isolated PostgreSQL and JARs."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time

import quality_gates as qa

ROOT = Path(__file__).resolve().parents[2]


def command(*args):
    deadline = time.time() + 60
    while True:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=60)
        if result.returncode == 0:
            return result.stdout.strip()
        if len(args) > 3 and args[3] == 'createdb' and time.time() < deadline:
            time.sleep(0.5)
            continue
        if result.stderr:
            print(result.stderr, flush=True)
        raise RuntimeError(f'Fixture command failed: {args[0]} {args[1] if len(args)>1 else ""}')


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def main():
    directory = ROOT / 'tmp' / ('return-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = [], [], []
    password, operator, service_token = (secrets.token_urlsafe(32) for _ in range(3))
    key = secrets.token_bytes(48)
    urls = {name: f'http://127.0.0.1:{port()}' for name in ('customer-service', 'returns-service', 'api-gateway')}

    def sql(database, query):
        return command('docker', 'exec', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'fixture', '-d', database, '-Atqc', query)

    def token(customer=1, email='owner@fixture.example', expired=False):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode(
            {'typ': 'access', 'sub': str(customer), 'email': email, 'exp': int(time.time()) + (-600 if expired else 600)})
        return unsigned + '.' + base64.urlsafe_b64encode(hmac.new(key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

    try:
        postgres = command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432',
                           '-e', 'POSTGRES_USER=fixture', '-e', 'POSTGRES_PASSWORD=' + password, 'postgres:16-alpine')
        db_port = command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'fixture'], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Fixture PostgreSQL startup timed out')
        for database in ('customer_db', 'returns_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in ('customer-service', 'returns-service', 'api-gateway'):
            database = 'returns_db' if name == 'returns-service' else 'customer_db'
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       RETURNS_SERVICE_URL=urls['returns-service'], ORDER_SERVICE_URL='http://127.0.0.1:1', PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=service_token,
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps({'returns-service': service_token}),
                       CUSTOMER_JWT_SECRET=base64.b64encode(key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       ORDER_SAGA_RECOVERY_ENABLED='false', COUPON_EXPIRY_ENABLED='false',
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {name} first'
            handle = (directory / (name + '.log')).open('w'); handles.append(handle)
            process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                       env=env, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
            processes.append(process)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'{name} exited; inspect fixture logs in {directory}')
                if qa.Client(urls[name], timeout=2).get('/actuator/health').status == 200:
                    break
                time.sleep(.3)
            else:
                raise RuntimeError(f'{name} fixture readiness timed out')

        sql('customer_db', """
            INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            VALUES ('owner@fixture.example','fixture-hash','Jan','Testowy','Testowa 1','00-001','Warszawa','Polska',true,now(),now()),
                   ('other@fixture.example','fixture-hash','Anna','Inna','Testowa 2','00-002','Kraków','Polska',true,now(),now()),
                   ('disabled@fixture.example','fixture-hash','Nie','Aktywna','Testowa 3','00-003','Kraków','Polska',false,now(),now())
            """)

        sql('returns_db', """
            INSERT INTO return_requests(id,order_id,customer_id,customer_email,request_type,reason,description,status,
              moderation_note,request_hash,order_verified_at,version,created_at,updated_at)
            SELECT n,1000+n,CASE WHEN n<=55 THEN 1 ELSE 2 END,'owner@fixture.example',
              CASE WHEN n%2=0 THEN 'COMPLAINT' ELSE 'RETURN' END,'Powód Żółć','Opis Żółć — tekst autora',
              (ARRAY['REQUESTED','UNDER_REVIEW','APPROVED','REJECTED','COMPLETED'])[(n-1)%5+1],
              'fixture-private-moderation',repeat('a',64),now(),7,now(),now() FROM generate_series(1,56) n;
            SELECT setval('return_requests_id_seq',56);
            UPDATE return_requests SET request_hash=NULL,order_verified_at=NULL WHERE id=55;
            INSERT INTO return_lines(return_id,order_item_id,product_id,product_name,ordered_quantity,quantity)
            SELECT 1,5000-n,n,'Produkt Żółć '||n,3,2 FROM generate_series(1,105) n;
            INSERT INTO return_lines(return_id,order_item_id,product_id,product_name,ordered_quantity,quantity)
            SELECT 56,5000-n,n,'Other',3,1 FROM generate_series(1,2) n;
            INSERT INTO return_events(return_id,revision,expected_version,operation_id,action,from_status,to_status,
              note,actor_ref,order_status,payment_state,created_at)
            SELECT 1,n-1,n-2,gen_random_uuid(),CASE WHEN n=1 THEN 'LEGACY_SNAPSHOT' ELSE 'STATUS_CHANGED' END,
              CASE WHEN n=1 THEN NULL ELSE 'REQUESTED' END,'UNDER_REVIEW','fixture-private-note',
              'private-operator@fixture.example','COMPLETED','PAID',now() FROM generate_series(1,105) n;
            INSERT INTO return_events(return_id,revision,action,to_status,note,actor_ref,created_at)
            VALUES(56,0,'LEGACY_SNAPSHOT','REQUESTED','fixture-private-other-note','private-operator@fixture.example',now())
            """)
        snapshot="SELECT json_build_array((SELECT json_agg(r ORDER BY id) FROM return_requests r),(SELECT json_agg(l ORDER BY id) FROM return_lines l),(SELECT json_agg(e ORDER BY id) FROM return_events e))::text"
        before=sql('returns_db',snapshot)
        header_fields={'returnId','orderId','customerEmail','requestType','reason','description','status','createdAt','updatedAt'}
        item_fields={'lineId','orderItemId','productId','productName','orderedQuantity','quantity'}
        event_fields={'eventId','action','fromStatus','toStatus','createdAt'}
        for name,prefix in [('returns-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]:
            client=qa.Client(urls[name],timeout=10);path=prefix+'/returns/me/data-export'
            owner={'Authorization':'Bearer '+token(),'X-Customer-Id':'2','X-Order-Access-Token':'G'*43}
            for suffix,collection,fields,scope,filename,expected_ids,cursor_field in (
                ('','returns',header_fields,'returns-page','returns-data-page.json',list(range(55,0,-1)),'returnId'),
                ('/1/items','items',item_fields,'return-items-page','return-items-data-page.json',list(range(4999,4894,-1)),'orderItemId'),
                ('/1/events','events',event_fields,'return-events-page','return-events-data-page.json',list(range(105,0,-1)),'eventId')):
                cursor,collected,pages='0',[],0
                statuses,types,line_ids=set(),set(),[]
                while True:
                    response=qa.status(client.get(path+suffix+f'?size=20&cursor={cursor}&customerId=2',headers=owner),200,'owned '+collection)
                    document=response.document();pages+=1
                    envelope={'schemaVersion','scope','complete','generatedAt',collection,'hasMore','nextCursor','omissions'}
                    if suffix:envelope.add('returnId');assert document['returnId']==1
                    assert set(document)==envelope and document['schemaVersion']==1 and document['complete'] is False and document['scope']==scope
                    rows=document[collection];assert len(rows)<=20 and all(set(row)==fields for row in rows)
                    if collection=='returns':
                        assert all(row['reason']=='Powód Żółć' and row['description']=='Opis Żółć — tekst autora' for row in rows)
                        statuses.update(row['status'] for row in rows);types.update(row['requestType'] for row in rows)
                    if collection=='items':
                        assert all(row['quantity']==2 and row['orderedQuantity']==3 for row in rows);line_ids.extend(row['lineId'] for row in rows)
                    if collection=='events':
                        for row in rows:
                            if row['eventId']==1:assert row['action']=='LEGACY_SNAPSHOT' and row['fromStatus'] is None
                        assert {'scope':'legacySnapshotTime','status':'migration-snapshot-not-original-event-time'} in document['omissions']
                    assert b'fixture-private' not in response.body and b'private-operator@' not in response.body and b'"requestHash"' not in response.body
                    assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
                    assert response.headers['content-disposition']==f'attachment; filename="{filename}"'
                    assert response.headers['x-content-type-options']=='nosniff'
                    collected.extend(row[cursor_field] for row in rows)
                    if not document['hasMore']:assert document['nextCursor']=='0';break
                    cursor=document['nextCursor'];assert cursor.isdigit() and pages<10
                assert collected==expected_ids and len(set(collected))==len(collected)
                if collection=='returns':assert pages==3 and statuses=={'REQUESTED','UNDER_REVIEW','APPROVED','REJECTED','COMPLETED'} and types=={'RETURN','COMPLAINT'}
                else:assert pages==6
                if collection=='items':assert line_ids==list(range(1,106))
            for suffix,collection in [('items','items'),('events','events')]:
                for case_id in (56,99999,0):qa.status(client.get(path+f'/{case_id}/{suffix}',headers=owner),404,'foreign or missing case')
                empty=qa.status(client.get(path+f'/55/{suffix}',headers=owner),200,'owned empty legacy case').document()
                assert empty[collection]==[] and empty['hasMore'] is False
                empty=qa.status(client.get(path+f'/1/{suffix}?cursor=1',headers=owner),200,'exhausted owned page').document()
                assert empty[collection]==[] and empty['hasMore'] is False
            other={'Authorization':'Bearer '+token(2,'other@fixture.example')}
            assert [row['returnId'] for row in qa.status(client.get(path,headers=other),200,'second owner').document()['returns']]==[56]
            assert len(qa.status(client.get(path+'/56/items',headers=other),200,'second owner items').document()['items'])==2
            assert len(qa.status(client.get(path+'/56/events',headers=other),200,'second owner events').document()['events'])==1
            for suffix in ('items','events'):qa.status(client.get(path+f'/1/{suffix}',headers=other),404,'second owner isolated')
            for route in (path,path+'/1/items',path+'/1/events'):
                for credential,expected in ((None,401),(operator,403),(service_token,403),('invalid',401),(token(expired=True),401),(token(3,'disabled@fixture.example'),401)):
                    headers={} if credential is None else {'Authorization':'Bearer '+credential}
                    qa.status(client.get(route,headers=headers),expected,'forbidden actor')
                for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):
                    qa.status(client.get(route+query,headers=owner),400,'invalid window')
                qa.status(client.get(route,method='POST',headers=owner),403,'wrong method')
            # The separate operator history route does not become customer-accessible.
            qa.status(client.get(prefix+'/returns/1/history',headers=owner),403,'operator history stays private')
            checks.append(name+prefix+': 55 cases, 105 items/events, indexed cursors, two owners, six denials, legacy/empty/not-found and private headers')
        assert sql('returns_db',snapshot)==before;checks.append('case/item/event rows unchanged; no Order fixture or domain actions needed')
        spec=qa.Client(urls['returns-service']).get('/v3/api-docs').document()
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['returns'],'Returns')
        for schema,fields in [('ReturnExportData',header_fields),('ReturnExportLine',item_fields),('ReturnExportEvent',event_fields)]:
            assert set(spec['components']['schemas'][schema]['properties'])==fields
        assert spec['components']['securitySchemes']['bearerAuth']['scheme']=='bearer'
        checks.append('OpenAPI contract and exact case/item/event field allowlists')
        report={'stage':'A43.23','status':'PASS','checks':checks,'realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(processes):
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for handle in handles:handle.close()
        if postgres:command('docker','rm','-f',postgres)


if __name__=='__main__':
    main()

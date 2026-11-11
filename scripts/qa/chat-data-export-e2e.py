#!/usr/bin/env python3
"""A43.26: capability-only conversation and bounded entry exports against isolated PostgreSQL and JARs."""
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
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

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
    directory = ROOT / 'tmp' / ('chat-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = {}, [], []
    password, operator, service_token = (secrets.token_urlsafe(32) for _ in range(3))
    signing_key = secrets.token_bytes(48)
    urls = {name: f'http://127.0.0.1:{port()}' for name in ('customer-service', 'chat-service', 'api-gateway')}

    def sql(database, query):
        return command('docker', 'exec', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'fixture', '-d', database, '-Atqc', query)

    def token(customer=1, email='owner@fixture.example', expired=False):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode(
            {'typ': 'access', 'sub': str(customer), 'email': email, 'exp': int(time.time()) + (-600 if expired else 600)})
        return unsigned + '.' + base64.urlsafe_b64encode(hmac.new(signing_key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

    notification_calls = []
    class NotificationProbe(BaseHTTPRequestHandler):
        def do_GET(self):
            notification_calls.append(self.command); self.send_response(503); self.end_headers()
        do_POST = do_GET
        def log_message(self, *args):
            pass
    notification = ThreadingHTTPServer(('127.0.0.1', 0), NotificationProbe)
    notification_thread = threading.Thread(target=notification.serve_forever, daemon=True)
    notification_thread.start()
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
        for database in ('customer_db', 'chat_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in ('customer-service', 'chat-service', 'api-gateway'):
            database = 'chat_db' if name == 'chat-service' else 'customer_db'
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       CHAT_SERVICE_URL=urls['chat-service'], ORDER_SERVICE_URL='http://127.0.0.1:1', PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=service_token,
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps({'chat-service': service_token}),
                       CUSTOMER_JWT_SECRET=base64.b64encode(signing_key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       CHAT_OUTBOX_ENABLED='false',
                       NOTIFICATION_SERVICE_URL=f'http://127.0.0.1:{notification.server_port}',
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {name} first'
            handle = (directory / (name + '.log')).open('w'); handles.append(handle)
            process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                       env=env, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
            processes[name] = process
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
        cap_a,cap_b,cap_empty=('A'*43,'B'*43,'C'*43)
        for conversation_id,capability in ((1,cap_a),(2,cap_b),(3,None),(4,cap_empty)):
            hash_value='NULL' if capability is None else "'"+hashlib.sha256(capability.encode()).hexdigest()+"'"
            sql('chat_db',f"""
                INSERT INTO chat_messages(id,customer_name,customer_email,subject,message,status,notification_status,reply_message,
                  reply_notification_status,created_at,updated_at,replied_at,access_token_hash)
                VALUES ({conversation_id},'Jan Żółć','owner@fixture.example','Temat Żółć','Pytanie Żółć','ANSWERED','fixture-private',
                  'Odpowiedź Żółć','fixture-private',now(),now(),now(),{hash_value})
                """)
        sql('chat_db', """
            SELECT setval('chat_messages_id_seq',4);
            INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at)
            SELECT 1,CASE WHEN n%2=1 THEN 'CUSTOMER' ELSE 'SUPPORT' END,n=1,'fixture-private-request-'||n,
              'Wpis Żółć '||n,now() FROM generate_series(1,105) n;
            INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at)
            VALUES(2,'CUSTOMER',true,'fixture-private-other','Inna rozmowa',now()),
                  (3,'CUSTOMER',true,'fixture-private-no-proof','Legacy bez dowodu',now());
            INSERT INTO chat_notification_outbox(entry_id,event_type,source_id,status,attempts,next_attempt_at,processing_token,processing_until,last_error,created_at)
            SELECT id,'CHAT_REPLY_SENT',conversation_id,'RETRY',3,now(),'fixture-private-lease',now()+interval '1 hour','fixture-private-error',now() FROM chat_entries
            """)
        snapshot="SELECT json_build_array((SELECT json_agg(m ORDER BY id) FROM chat_messages m),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o ORDER BY entry_id) FROM chat_notification_outbox o))::text"
        before=sql('chat_db',snapshot)
        conversation_fields={'conversationId','customerName','customerEmail','subject','status','initialMessage','latestReply','createdAt','updatedAt','repliedAt'}
        entry_fields={'entryId','author','initialMessage','message','createdAt'}
        targets=[('chat-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]
        for name,prefix in targets:
            client=qa.Client(urls[name],timeout=10);base=prefix+'/chat/conversations/'
            proof={'X-Chat-Access-Token':cap_a}
            response=qa.status(client.get(base+'1/data-export',headers=proof),200,'anonymous conversation proof')
            doc=response.document();assert set(doc)=={'schemaVersion','scope','complete','generatedAt','authorizationMode','conversation','omissions'}
            assert doc['scope']=='chat-conversation' and doc['complete'] is False and doc['authorizationMode']=='conversation-capability'
            assert set(doc['conversation'])==conversation_fields and doc['conversation']['conversationId']==1
            assert doc['conversation']['customerName']=='Jan Żółć' and doc['conversation']['initialMessage']=='Pytanie Żółć' and doc['conversation']['latestReply']=='Odpowiedź Żółć'
            assert {'scope':'accountAssociation','status':'not-stored-not-inferred'} in doc['omissions']
            assert response.headers['content-disposition']=='attachment; filename="chat-conversation-data.json"'
            assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
            cursor,collected,pages='0',[],0
            while True:
                response=qa.status(client.get(base+f'1/data-export/entries?size=50&cursor={cursor}&customerId=2',headers=proof),200,'bounded conversation entries')
                doc=response.document();pages+=1
                assert set(doc)=={'schemaVersion','scope','complete','generatedAt','authorizationMode','conversationId','entries','hasMore','nextCursor','omissions'}
                assert doc['scope']=='chat-entries-page' and doc['complete'] is False and doc['authorizationMode']=='conversation-capability' and doc['conversationId']==1
                assert len(doc['entries'])<=50 and all(set(row)==entry_fields for row in doc['entries'])
                for row in doc['entries']:
                    assert row['message']==f"Wpis Żółć {row['entryId']}" and row['author']==('CUSTOMER' if row['entryId']%2 else 'SUPPORT')
                    assert row['initialMessage']==(row['entryId']==1)
                assert response.headers['content-disposition']=='attachment; filename="chat-entries-data-page.json"'
                assert 'no-store' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
                for secret in (cap_a,cap_b,hashlib.sha256(cap_a.encode()).hexdigest(),'fixture-private'):
                    assert secret.encode() not in response.body
                collected.extend(row['entryId'] for row in doc['entries'])
                if not doc['hasMore']:assert doc['nextCursor']=='0';break
                cursor=doc['nextCursor'];assert cursor.isdigit() and pages<10
            assert pages==3 and collected==list(range(105,0,-1))
            for suffix in ('','/entries'):
                route=base+'1/data-export'+suffix
                for headers in ({},{'X-Chat-Access-Token':'short'},{'X-Chat-Access-Token':'!'*43},{'X-Chat-Access-Token':'A'*129},
                                {'Authorization':'Bearer '+token()},{'Authorization':'Bearer '+token(2,'other@fixture.example')},
                                {'X-Customer-Id':'1','X-Customer-Email':'owner@fixture.example'}):
                    qa.status(client.get(route,headers=headers),401,'no valid conversation proof')
                qa.status(client.get(route+'?accessToken='+cap_a),401,'query token is not credential')
                for conversation_id,capability in ((1,cap_b),(2,cap_a),(3,cap_a),(99999,cap_a),(0,cap_a)):
                    qa.status(client.get(base+str(conversation_id)+'/data-export'+suffix,headers={'X-Chat-Access-Token':capability}),404,'wrong/missing/null-hash conversation')
                for credential,expected in ((operator,403),(service_token,403),('invalid',401),(token(expired=True),401),(token(3,'disabled@fixture.example'),401)):
                    qa.status(client.get(route,headers={'Authorization':'Bearer '+credential,'X-Chat-Access-Token':cap_a}),expected,'no privileged/authentication bypass')
                # A different account with the proof may read: access belongs to capability, not to JWT/email.
                qa.status(client.get(route,headers={'Authorization':'Bearer '+token(2,'other@fixture.example'),'X-Chat-Access-Token':cap_a}),200,'capability independent of account identity')
                qa.status(client.get(route,method='POST',headers=proof),401,'anonymous wrong method')
                qa.status(client.get(route,method='POST',headers={'Authorization':'Bearer '+token(),'X-Chat-Access-Token':cap_a}),403,'authenticated wrong method')
            assert len(qa.status(client.get(base+'2/data-export/entries',headers={'X-Chat-Access-Token':cap_b}),200,'second conversation proof').document()['entries'])==1
            empty=qa.status(client.get(base+'4/data-export/entries',headers={'X-Chat-Access-Token':cap_empty}),200,'empty historical conversation').document()
            assert empty['entries']==[] and empty['hasMore'] is False
            assert qa.status(client.get(base+'1/data-export/entries?cursor=1',headers=proof),200,'exhausted page').document()['entries']==[]
            for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):
                qa.status(client.get(base+'1/data-export/entries'+query,headers=proof),400,'invalid window')
            qa.status(client.get(prefix+'/chat/messages',headers=proof),401,'no support conversation listing access')
            checks.append(name+prefix+': capability-only header/105 entries, same-email conversation isolation, JWT insufficient, no privileged bypass, empty/legacy/not-found and private headers')
        assert sql('chat_db',snapshot)==before
        checks.append('all three Chat tables unchanged; no account binding or reconstructed events')
        spec=qa.Client(urls['chat-service']).get('/v3/api-docs').document()
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['chat'],'Chat')
        assert set(spec['components']['schemas']['ChatExportConversation']['properties'])==conversation_fields
        assert set(spec['components']['schemas']['ChatExportEntry']['properties'])==entry_fields
        scheme=spec['components']['securitySchemes']['chatCapability']
        assert scheme['type']=='apiKey' and scheme['in']=='header' and scheme['name']=='X-Chat-Access-Token'
        checks.append('OpenAPI contract, exact field allowlists and capability header security scheme')
        processes['customer-service'].terminate();processes['customer-service'].wait(timeout=15)
        for name,prefix in targets:
            client=qa.Client(urls[name],timeout=10)
            for suffix in ('','/entries'):
                qa.status(client.get(prefix+'/chat/conversations/1/data-export'+suffix,headers={'X-Chat-Access-Token':cap_a}),200,'capability export independent of account service')
        assert sql('chat_db',snapshot)==before
        checks.append('anonymous capability exports still work after stopping our Customer fixture; no account dependency')
        assert notification_calls==[]
        checks.append('local notification probe received zero requests')
        report={'stage':'A43.26','status':'PASS','checks':checks,'notificationRequests':len(notification_calls),
                'decision':'capability-only-no-account-binding','realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(list(processes.values())):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for handle in handles:handle.close()
        if postgres:command('docker','rm','-f',postgres)
        notification.shutdown();notification.server_close();notification_thread.join(timeout=5)


if __name__=='__main__':
    main()

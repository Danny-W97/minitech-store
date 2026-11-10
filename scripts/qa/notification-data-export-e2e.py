#!/usr/bin/env python3
"""A43.27: isolated PostgreSQL/JAR tests; never reads runtime credentials or user data."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import socketserver
import subprocess
import threading
import time
import quality_gates as qa

ROOT=Path(__file__).resolve().parents[2]

import sys

def command(*args):
    deadline = time.time() + 60
    while True:
        try:
            return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
        except subprocess.CalledProcessError as e:
            if len(args) > 3 and args[3] == 'createdb' and time.time() < deadline:
                time.sleep(0.5)
                continue
            if e.stderr:
                print(f"COMMAND FAILED: {args}\nSTDERR:\n{e.stderr}", file=sys.stderr, flush=True)
            raise

def port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0));return listener.getsockname()[1]

def main():
    directory=ROOT/'tmp'/('notification-export-'+secrets.token_hex(4));directory.mkdir(parents=True)
    names=('customer-service','order-service','chat-service','notification-service','api-gateway')
    urls={name:f'http://127.0.0.1:{port()}' for name in names}
    credentials={name:secrets.token_urlsafe(32) for name in names}
    password,operator=secrets.token_urlsafe(32),secrets.token_urlsafe(32)
    signing_key=secrets.token_bytes(48);processes={};handles=[];checks=[];postgres=None;send_attempts=[]
    class RejectSMTP(socketserver.BaseRequestHandler):
        def handle(self):send_attempts.append(True);self.request.sendall(b'421 fixture prohibits delivery\r\n')
    smtp=socketserver.ThreadingTCPServer(('127.0.0.1',0),RejectSMTP)
    thread=threading.Thread(target=smtp.serve_forever,daemon=True);thread.start()
    def sql(database,query):return command('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d',database,'-Atqc',query)
    def token(customer=1,email='owner@fixture.example',expired=False):
        encode=lambda item:base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned=encode({'alg':'HS256','typ':'JWT'})+'.'+encode({'typ':'access','sub':str(customer),'email':email,'exp':int(time.time())+(-600 if expired else 600)})
        return unsigned+'.'+base64.urlsafe_b64encode(hmac.new(signing_key,unsigned.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    def stop(name):processes[name].terminate();processes[name].wait(timeout=15)
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-U','fixture'],capture_output=True).returncode==0:break
            time.sleep(.5)
        else:raise RuntimeError('Fixture PostgreSQL startup timed out')
        for database in ('customer_db','order_db','chat_db','notification_db'):command('docker','exec',postgres,'createdb','-U','fixture',database)
        for name in names:
            database={'order-service':'order_db','chat-service':'chat_db','notification-service':'notification_db'}.get(name,'customer_db')
            env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],
                DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}',DB_USERNAME='fixture',DB_PASSWORD=password,
                CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=urls['customer-service'],
                CHAT_SERVICE_URL=urls['chat-service'],NOTIFICATION_SERVICE_URL=urls['notification-service'],
                ORDER_SERVICE_URL=urls['order-service'],PLATFORM_ORDER_URL=urls['order-service'],
                PLATFORM_OPERATOR_TOKEN=operator,PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
                CUSTOMER_JWT_SECRET=base64.b64encode(signing_key).decode(),CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                CUSTOMER_SECURITY_RETENTION_ENABLED='false',CUSTOMER_AUDIT_RETENTION_MODE='off',CHAT_OUTBOX_ENABLED='false',
                ORDER_SAGA_RECOVERY_ENABLED='false',COUPON_EXPIRY_ENABLED='false',OUTBOX_PUBLISHER_ENABLED='false',
                NOTIFICATION_CONSUMER_ENABLED='false',NOTIFICATION_RETRY_ENABLED='false',
                NOTIFICATION_SENT_RETENTION_MODE='off',NOTIFICATION_REJECTED_INBOX_RETENTION_MODE='off',NOTIFICATION_RESOLVED_DLQ_RETENTION_MODE='off',
                MAIL_HOST='127.0.0.1',MAIL_PORT=str(smtp.server_address[1]),
                GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',MANAGEMENT_HEALTH_MAIL_ENABLED='false',
                SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars=list((ROOT/name/'target').glob('*-SNAPSHOT.jar'));assert len(jars)==1,f'Package {name} first'
            handle=(directory/(name+'.log')).open('w');handles.append(handle)
            processes[name]=subprocess.Popen(['java','-Xms48m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(jars[0])],env=env,cwd=ROOT,stdout=handle,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if processes[name].poll() is not None:raise RuntimeError(f'{name} exited; fixture logs: {directory}')
                if qa.Client(urls[name],timeout=2).get('/actuator/health').status==200:break
                time.sleep(.3)
            else:raise RuntimeError(f'{name} fixture startup timed out')
        sql('customer_db',"""
            INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            VALUES ('owner@fixture.example','fixture-hash','Jan','Test','Testowa 1','00-001','Warszawa','PL',true,now(),now()),
                   ('other@fixture.example','fixture-hash','Anna','Test','Testowa 2','00-002','Kraków','PL',true,now(),now())
            """)
        sql('order_db',"""
            INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              fulfillment_type,idempotency_key,guest_access_token_hash,created_at,updated_at)
            SELECT n,CASE WHEN n=2 THEN 2 WHEN n=3 THEN NULL ELSE 1 END,'same@fixture.example',123.45,'PAID',
              'Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,'HOME_DELIVERY',
              'fixture-'||n,repeat('a',64),now(),now() FROM generate_series(1,4) n
            """)
        cap='C'*43;other_cap='D'*43
        for conversation_id,capability in ((1,cap),(2,other_cap),(3,None),(4,'E'*43)):
            hashed='NULL' if capability is None else "'"+hashlib.sha256(capability.encode()).hexdigest()+"'"
            sql('chat_db',f"""
                INSERT INTO chat_messages(id,customer_name,customer_email,subject,message,status,notification_status,created_at,updated_at,access_token_hash)
                VALUES ({conversation_id},'Jan','same@fixture.example','Temat','Pytanie','NEW','PENDING',now(),now(),{hashed})
                """)
        sql('chat_db',"""
            INSERT INTO chat_entries(id,conversation_id,author,initial_message,request_key,message,created_at)
            VALUES (1,1,'SUPPORT',false,'reply','Odpowiedź',now()),(2,1,'CUSTOMER',true,'initial','Pytanie',now()),
                   (3,2,'SUPPORT',false,'other','Inna odpowiedź',now())
            """)
        sql('notification_db',"""
            INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at,sent_at,provider_reference,failure_reason)
            SELECT 'PAYMENT_SUCCEEDED',1,'same'||n||'@fixture.example','EMAIL','Temat Żółć','Wiadomość Żółć','SENT',now(),now(),now(),'fixture-private-provider',null FROM generate_series(1,105) n;
            INSERT INTO notifications(event_type,source_id,subject_customer_id,recipient,channel,subject,message,status,created_at,updated_at,sent_at)
            SELECT type,source,CASE WHEN type='PASSWORD_RESET_REQUESTED' THEN source ELSE NULL END,CASE WHEN type IN ('CHAT_REPLY_SENT','CHAT_ENTRY_REPLIED') THEN 'same@fixture.example' ELSE 'same1@fixture.example' END,'EMAIL','Temat Żółć',CASE WHEN type='PASSWORD_RESET_REQUESTED' THEN 'fixture-private-reset-secret' ELSE 'Wiadomość Żółć' END,'SENT',now(),now(),now()
            FROM (VALUES ('PAYMENT_SUCCEEDED',2),('ORDER_CREATED',3),('CUSTOMER_REGISTERED',1),('PASSWORD_RESET_REQUESTED',1),
              ('CHAT_REPLY_SENT',1),('CHAT_MESSAGE_RECEIVED',1),('CHAT_ENTRY_REPLIED',1),('CHAT_ENTRY_RECEIVED',2),('CHAT_ENTRY_REPLIED',3),('UNKNOWN',1)) v(type,source);
            INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at)
            VALUES ('CHAT_ENTRY_REPLIED',1,'support@fixture.example','EMAIL','Internal','fixture-private-internal','SENT',now(),now());
            UPDATE notifications SET subject=null,message=null,content_compacted_at=now(),provider_reference=null WHERE id=105
            """)
        snapshots={'notification_db':"SELECT json_build_array((SELECT json_agg(n ORDER BY id) FROM notifications n),(SELECT json_agg(i) FROM event_inbox i),(SELECT json_agg(d) FROM notification_dead_letters d))::text",
            'order_db':"SELECT json_agg(o ORDER BY id)::text FROM orders o",
            'chat_db':"SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM chat_messages c),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o) FROM chat_notification_outbox o))::text"}
        before={database:sql(database,query) for database,query in snapshots.items()}
        targets=[('notification-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]
        for name,prefix in targets:
            client=qa.Client(urls[name],timeout=10);route=prefix+'/notifications/me/data-export/orders/'
            headers={'Authorization':'Bearer '+token()};cursor='0';collected=[]
            while True:
                response=qa.status(client.get(route+f'1?size=50&cursor={cursor}&customerId=2',headers=headers),200,'owned notification page')
                doc=response.document();assert doc['complete'] is False and doc['scope']=='order-notifications'
                assert {'area':'accountSecurityNotifications','reason':'manual-source-proof-no-security-links'} in doc['omissions']
                assert {'area':'inboxAndDeadLetters','reason':'covered-domain-data-operational-excluded-manual-review'} in doc['omissions']
                assert set(doc)=={'schemaVersion','scope','complete','exportedAt','authorizationMode','sourceId','conversationId','items','hasMore','nextCursor','omissions'}
                assert len(doc['items'])<=50
                assert all(set(row)=={'notificationId','eventType','sourceId','recipient','channel','subject','message','status','contentDisposition','contentCompactedAt','createdAt','updatedAt','sentAt'} for row in doc['items'])
                assert all(row['sourceId']==1 and row['eventType']=='PAYMENT_SUCCEEDED' for row in doc['items'])
                assert response.headers['content-disposition']=='attachment; filename="notification-data-page.json"'
                assert 'private' in response.headers['cache-control'] and 'no-store' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
                assert b'fixture-private' not in response.body
                if not collected:assert doc['items'][0]['contentDisposition']=='COMPACTED' and doc['items'][0]['message'] is None
                collected.extend(row['notificationId'] for row in doc['items'])
                if not doc['hasMore']:assert doc['nextCursor']=='0';break
                cursor=doc['nextCursor'];assert len(collected)<150
            assert collected==list(range(105,0,-1))
            for credential,expected in ((None,401),(operator,403),(credentials['notification-service'],403),('invalid',401),(token(expired=True),401)):
                qa.status(client.get(route+'1',headers={} if credential is None else {'Authorization':'Bearer '+credential}),expected,'no account role bypass')
            for order_id in (2,3,99999):qa.status(client.get(route+str(order_id),headers=headers),404,'other guest or missing source')
            own_other=qa.status(client.get(route+'2',headers={'Authorization':'Bearer '+token(2,'other@fixture.example')}),200,'same recipient different order owner').document()
            assert len(own_other['items'])==1
            assert qa.status(client.get(route+'4',headers=headers),200,'owned source without notifications').document()['items']==[]
            for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):qa.status(client.get(route+'1'+query,headers=headers),400,'invalid window')
            for path in ('/notifications','/notifications/1','/notifications/source/1','/notifications/dead-letter'):
                qa.status(client.get(prefix+path,headers=headers),403,'operational notification api stays private')
            qa.status(client.get(prefix+'/notifications/dead-letter/1/replay',method='POST',headers=headers),403,'export does not grant replay')
            checks.append(name+prefix+': 105 notifications, source and event-type isolation, compaction, private headers, role and ownership checks')
            chat_route=prefix+'/notifications/chat/data-export/';chat_proof={'X-Chat-Access-Token':cap}
            for event_type in ('CHAT_REPLY_SENT','CHAT_ENTRY_REPLIED'):
                response=qa.status(client.get(chat_route+event_type+'/1',headers=chat_proof),200,'customer-directed chat notification')
                doc=response.document();assert doc['scope']=='chat-source-notifications' and doc['authorizationMode']=='conversation-capability' and doc['conversationId']==1
                assert len(doc['items'])==1 and doc['items'][0]['eventType']==event_type and doc['items'][0]['message']=='Wiadomość Żółć'
                assert {'area':'supportDirectedChatNotifications','reason':'excluded-user-decision'} in doc['omissions']
                assert cap.encode() not in response.body and b'fixture-private' not in response.body
                assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
            path=chat_route+'CHAT_ENTRY_REPLIED/1'
            for proof in ({},{'X-Chat-Access-Token':'short'},{'Authorization':'Bearer '+token()},{'X-Customer-Id':'1'}):
                qa.status(client.get(path,headers=proof),401,'chat token is required independently of account')
            qa.status(client.get(path+'?accessToken='+cap),401,'query token is not proof')
            qa.status(client.get(path,headers={'X-Chat-Access-Token':other_cap}),404,'wrong conversation capability')
            for event_type in ('CHAT_MESSAGE_RECEIVED','CHAT_ENTRY_RECEIVED','PAYMENT_SUCCEEDED','UNKNOWN'):
                qa.status(client.get(chat_route+event_type+'/1',headers=chat_proof),404,'support and unknown types excluded')
            for source_id in (2,3,99999):qa.status(client.get(chat_route+'CHAT_ENTRY_REPLIED/'+str(source_id),headers=chat_proof),404,'wrong author other or absent source')
            qa.status(client.get(chat_route+'CHAT_REPLY_SENT/3',headers=chat_proof),404,'legacy source without hash')
            assert qa.status(client.get(chat_route+'CHAT_REPLY_SENT/4',headers={'X-Chat-Access-Token':'E'*43}),200,'authorized empty chat notifications').document()['items']==[]
            for credential in (operator,credentials['notification-service']):
                qa.status(client.get(path,headers={'Authorization':'Bearer '+credential,'X-Chat-Access-Token':cap}),403,'no privileged chat bypass')
            qa.status(client.get(path,headers={'Authorization':'Bearer '+token(2,'other@fixture.example'),'X-Chat-Access-Token':cap}),200,'capability works independently of account identity')
            for query in ('?size=0','?size=51','?cursor=-1'):qa.status(client.get(path+query,headers=chat_proof),400,'chat bounded window')
            checks.append(name+prefix+': customer-directed chat replies only, exact conversation and entry proof, excluded support notifications, role and capability isolation')
        # Direct internal proof is restricted to Notification plus a conversation capability.
        client=qa.Client(urls['chat-service'],timeout=10);path='/internal/chat/notification-sources/CHAT_ENTRY_REPLIED/1/access'
        proof={'Authorization':'Bearer '+credentials['notification-service'],'X-Chat-Access-Token':cap}
        result=qa.status(client.get(path,headers=proof),200,'minimal chat source proof').document()
        assert result=={'eventType':'CHAT_ENTRY_REPLIED','sourceId':1,'conversationId':1,'recipientHash':hashlib.sha256(b'same@fixture.example').hexdigest()}
        for credential,expected in ((operator,403),(credentials['chat-service'],403),(token(),403)):
            qa.status(client.get(path,headers={'Authorization':'Bearer '+credential,'X-Chat-Access-Token':cap}),expected,'proof service isolation')
        qa.status(client.get(path,headers={'Authorization':'Bearer '+credentials['notification-service']}),401,'proof requires capability')
        qa.status(client.get(path,headers={'Authorization':'Bearer '+credentials['notification-service'],'X-Chat-Access-Token':other_cap}),404,'wrong proof')
        checks.append('Chat internal proof uses exact source graph, service identity and capability; private recipient digest, no raw address or text')
        spec=qa.Client(urls['notification-service']).get('/v3/api-docs').document()
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['notification'],'Notification')
        assert spec['components']['securitySchemes']['chatCapability']['name']=='X-Chat-Access-Token'
        assert set(spec['components']['schemas']['NotificationExportEntry']['properties'])=={'notificationId','eventType','sourceId','recipient','channel','subject','message','status','contentDisposition','contentCompactedAt','createdAt','updatedAt','sentAt'}
        checks.append('OpenAPI projection field allowlist and contract')
        for database,query in snapshots.items():assert sql(database,query)==before[database]
        assert send_attempts==[];checks.append('Notification and Chat tables unchanged; zero SMTP connections')
        stop('order-service')
        for name,prefix in targets:
            qa.status(qa.Client(urls[name],timeout=15).get(prefix+'/notifications/me/data-export/orders/1',headers={'Authorization':'Bearer '+token()}),503,'source unavailable fails closed')
        checks.append('Order authority unavailable returns 503 without fallback to recipient or cached authority')
        stop('chat-service')
        for name,prefix in targets:
            qa.status(qa.Client(urls[name],timeout=15).get(prefix+'/notifications/chat/data-export/CHAT_ENTRY_REPLIED/1',headers={'X-Chat-Access-Token':cap}),503,'chat source unavailable fails closed')
        for database,query in snapshots.items():assert sql(database,query)==before[database]
        assert send_attempts==[];checks.append('Chat authority unavailable returns 503; no fallback, writes or SMTP connections')
        report={'stage':'A43.27','status':'PASS','checks':checks,'smtpConnections':len(send_attempts),
            'decision':'customer-directed-chat-notifications-only','realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':report['status'],'checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(list(processes.values())):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for handle in handles:handle.close()
        if postgres:command('docker','rm','-f',postgres)
        smtp.shutdown();smtp.server_close();thread.join(timeout=5)

if __name__=='__main__':main()

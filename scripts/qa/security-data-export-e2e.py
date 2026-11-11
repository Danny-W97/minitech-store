#!/usr/bin/env python3
"""A43.29: disposable Customer/Gateway PostgreSQL fixture; no user data or credentials."""
import base64,hashlib,hmac,json,os,secrets,socket,subprocess,threading,time
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import quality_gates as qa

ROOT=Path(__file__).resolve().parents[2]
def command(*args):
    deadline = time.time() + 60
    while True:
        try:
            return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
        except subprocess.CalledProcessError as e:
            if len(args) > 3 and args[3] == 'createdb' and time.time() < deadline:
                time.sleep(0.5)
                continue
            raise
def port():
    with socket.socket() as listener:listener.bind(('127.0.0.1',0));return listener.getsockname()[1]

def main():
    directory=ROOT/'tmp'/('security-export-'+secrets.token_hex(4));directory.mkdir(parents=True)
    names=('customer-service','api-gateway');urls={n:f'http://127.0.0.1:{port()}' for n in names};credentials={n:secrets.token_urlsafe(32) for n in names}
    password,operator=secrets.token_urlsafe(32),secrets.token_urlsafe(32);signing_key=secrets.token_bytes(48)
    postgres=None;processes={};handles=[];checks=[];calls=[]
    class Probe(BaseHTTPRequestHandler):
        def do_GET(self):calls.append(self.command);self.send_response(503);self.end_headers()
        do_POST=do_GET
        def log_message(self,*args):pass
    probe=ThreadingHTTPServer(('127.0.0.1',0),Probe);thread=threading.Thread(target=probe.serve_forever,daemon=True);thread.start()
    def sql(query):return command('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d','customer_db','-Atqc',query)
    def token(customer=1,email='owner@fixture.example',expired=False):
        encode=lambda x:base64.urlsafe_b64encode(json.dumps(x).encode()).rstrip(b'=').decode()
        unsigned=encode({'alg':'HS256','typ':'JWT'})+'.'+encode({'typ':'access','sub':str(customer),'email':email,'exp':int(time.time())+(-600 if expired else 600)})
        return unsigned+'.'+base64.urlsafe_b64encode(hmac.new(signing_key,unsigned.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-U','fixture'],capture_output=True).returncode==0:break
            time.sleep(.5)
        else:raise RuntimeError('Fixture PostgreSQL startup timed out')
        command('docker','exec',postgres,'createdb','-U','fixture','customer_db')
        for name in names:
            env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/customer_db',DB_USERNAME='fixture',DB_PASSWORD=password,
                CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=urls['customer-service'],PLATFORM_OPERATOR_TOKEN=operator,PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
                CUSTOMER_JWT_SECRET=base64.b64encode(signing_key).decode(),CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                CUSTOMER_SECURITY_RETENTION_ENABLED='false',CUSTOMER_AUDIT_RETENTION_MODE='off',NOTIFICATION_SERVICE_URL=f'http://127.0.0.1:{probe.server_port}',
                GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars=list((ROOT/name/'target').glob('*-SNAPSHOT.jar'));assert len(jars)==1,f'Package {name} first'
            handle=(directory/(name+'.log')).open('w');handles.append(handle)
            processes[name]=subprocess.Popen(['java','-Xms48m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(jars[0])],env=env,cwd=ROOT,stdout=handle,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if processes[name].poll() is not None:raise RuntimeError(f'{name} exited; fixture logs: {directory}')
                if qa.Client(urls[name],timeout=2).get('/actuator/health').status==200:break
                time.sleep(.3)
            else:raise RuntimeError(f'{name} fixture startup timed out')
        sql("""
            INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            SELECT n,CASE WHEN n=1 THEN 'owner@fixture.example' WHEN n=2 THEN 'other@fixture.example' ELSE 'disabled@fixture.example' END,
              'private-password','Jan','Test','Testowa 1','00-001','Warszawa','PL',n<>3,now(),now() FROM generate_series(1,3) n;
            INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,last_used_time_step,created_at,updated_at)
            VALUES (1,'private-ciphertext',true,777,now(),now());
            INSERT INTO audit_events(subject_customer_id,event_type,actor,resource,outcome,correlation_id,client_ip,user_agent,metadata,occurred_at)
            SELECT CASE WHEN n%2=1 THEN 1 ELSE NULL END,CASE WHEN n%2=1 THEN 'LOGIN_SUCCESS' ELSE 'TOKEN_REFRESH' END,
              '1',CASE WHEN n%2=1 THEN 'CUSTOMER' ELSE 'REFRESH_TOKEN' END,'SUCCESS','private-correlation','private-ip','private-agent','private-other-person',now() FROM generate_series(1,105) n;
            INSERT INTO audit_events(subject_customer_id,event_type,actor,resource,outcome,correlation_id,metadata,occurred_at)
            VALUES (2,'LOGIN_SUCCESS','1','CUSTOMER','SUCCESS','private-correlation','private-other-person',now()),
              (NULL,'LOGIN_SUCCESS','owner@fixture.example','CUSTOMER','SUCCESS','private-correlation','private',now()),
              (NULL,'LOGIN_SUCCESS','1','CUSTOMER','SUCCESS','private-correlation','private',now()),
              (NULL,'TOKEN_REFRESH','01','REFRESH_TOKEN','SUCCESS','private-correlation','private',now()),
              (NULL,'TOKEN_REFRESH','1','CUSTOMER','SUCCESS','private-correlation','private',now()),
              (1,'private-unknown','1','CUSTOMER','SUCCESS','private-correlation','private',now());
            INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at,used_at)
            SELECT 1,md5(n::text),now(),CASE WHEN n%2=0 THEN now() ELSE NULL END FROM generate_series(1,105) n;
            INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at) VALUES(2,md5('other'),now());
            INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,backup_state,public_key_credential_type,attestation_object,created,label)
            SELECT concat('private-credential-',n),'MQ',decode('deadbeef','hex'),777,true,true,false,'public-key',decode('beef','hex'),now(),'private-other-person-label' FROM generate_series(1,105) n;
            INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,backup_state,public_key_credential_type,created,label)
            VALUES ('private-other-credential','Mg',decode('deadbeef','hex'),999,false,false,false,'public-key',now(),'private-other-label')
            """)
        snapshot="""SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM customers c),(SELECT json_agg(a ORDER BY id) FROM audit_events a),
            (SELECT json_agg(t ORDER BY id) FROM customer_totp_methods t),(SELECT json_agg(r ORDER BY id) FROM customer_recovery_codes r),
            (SELECT json_agg(w ORDER BY export_id) FROM user_credentials w),(SELECT json_agg(f ORDER BY id) FROM refresh_tokens f),
            (SELECT json_agg(p ORDER BY id) FROM password_reset_tokens p))::text"""
        before=sql(snapshot);headers={'Authorization':'Bearer '+token()}
        pages={'events':({'eventId','eventType','outcome','occurredAt','subjectProof'},'eventId'),
            'passkeys':({'exportId','createdAt','lastUsedAt','userVerificationInitialized','backupEligible','backupState'},'exportId'),
            'recovery-codes':({'recordId','createdAt','usedAt'},'recordId')}
        for name,prefix in [('customer-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]:
            client=qa.Client(urls[name],timeout=10);base=prefix+'/customers/me/data-export/security/'
            response=qa.status(client.get(base+'methods?customerId=2',headers={**headers,'X-Customer-Id':'2'}),200,'own method metadata')
            doc=response.document();assert set(doc)=={'schemaVersion','scope','complete','generatedAt','totp','omissions'} and doc['complete'] is False
            assert set(doc['totp'])=={'enabled','createdAt','updatedAt','enrollmentExpiresAt'} and doc['totp']['enabled'] is True
            assert 'private' in response.headers['cache-control'] and 'no-store' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
            assert response.headers['content-disposition']=='attachment; filename="security-methods.json"' and b'private-' not in response.body
            for suffix,(fields,item_id) in pages.items():
                cursor='0';ids=[]
                while True:
                    response=qa.status(client.get(base+suffix+f'?size=50&cursor={cursor}&customerId=2',headers=headers),200,'own bounded security page')
                    doc=response.document();assert set(doc)=={'schemaVersion','scope','complete','generatedAt','items','hasMore','nextCursor','omissions'}
                    assert doc['complete'] is False and len(doc['items'])<=50 and all(set(row)==fields for row in doc['items'])
                    assert b'private-' not in response.body and b'owner@fixture.example' not in response.body and b'other@fixture.example' not in response.body
                    assert {'area':'freeTextNotesLabelsRawMetadataIpUserAgentAndActor','reason':'manual-review-and-redaction-procedure'} in doc['omissions']
                    assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
                    ids.extend(row[item_id] for row in doc['items'])
                    if not doc['hasMore']:assert doc['nextCursor']=='0';break
                    cursor=doc['nextCursor'];assert len(ids)<150
                assert ids==list(range(105,0,-1))
                assert qa.status(client.get(base+suffix+'?cursor=1',headers=headers),200,'owned exhausted page').document()['items']==[]
                other=qa.status(client.get(base+suffix,headers={'Authorization':'Bearer '+token(2,'other@fixture.example')}),200,'separate security owner').document()
                assert len(other['items'])==1
                for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):qa.status(client.get(base+suffix+query,headers=headers),400,'invalid window')
            for suffix in ('methods',*pages):
                for credential in (None,operator,credentials['customer-service'],'invalid',token(expired=True),token(3,'disabled@fixture.example'),token(1,'other@fixture.example')):
                    qa.status(client.get(base+suffix,headers={} if credential is None else {'Authorization':'Bearer '+credential}),401,'no privileged or stale identity bypass')
            checks.append(name+prefix+': three 105-row pages, safe TOTP metadata, exact fields, active subject isolation, strict legacy refresh and private headers')
        assert sql(snapshot)==before and calls==[];checks.append('seven Customer/security tables unchanged; zero notification requests')
        spec=qa.Client(urls['customer-service']).get('/v3/api-docs').document();contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['customer'],'Customer')
        policy=json.loads((ROOT/'scripts/qa/contracts/security-export-policy.json').read_text(encoding='utf-8'))
        assert policy['decisions']['auditOwnership']=='explicit-subject-plus-strict-legacy-refresh-id' and policy['decisions']['freeText']=='manual-subject-review-and-redaction'
        assert not policy['secretDisclosureAllowed'] and not policy['automaticEmailInference'] and not policy['manualExportAutomated']
        assert (ROOT/policy['procedure']).is_file();checks.append('OpenAPI contract and explicit field decisions/manual redaction procedure')
        client=qa.Client(urls['customer-service']);profile={'firstName':'Jan','lastName':'Test','street':'Testowa 1','postalCode':'00-001','city':'Warszawa','country':'PL','phoneNumber':None}
        qa.status(client.get('/customers/1/profile',method='PUT',body=profile,headers=headers),200,'fixture profile write records explicit audit subject')
        assert sql("SELECT subject_customer_id FROM audit_events WHERE event_type='CUSTOMER_PROFILE_UPDATED' ORDER BY id DESC LIMIT 1")=='1'
        after_write=sql(snapshot);event=qa.status(client.get('/customers/me/data-export/security/events',headers=headers),200,'new audit event projection').document()['items'][0]
        assert event['eventType']=='CUSTOMER_PROFILE_UPDATED' and event['subjectProof']=='subject-customer-id'
        assert sql(snapshot)==after_write and calls==[];checks.append('real fixture profile update records explicit subject; its export causes no additional writes')
        sql('UPDATE customers SET enabled=false WHERE id=1')
        for name,prefix in [('customer-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]:
            for suffix in ('methods',*pages):qa.status(qa.Client(urls[name]).get(prefix+'/customers/me/data-export/security/'+suffix,headers=headers),401,'owner disabled between pages')
        checks.append('disabling owner revokes every metadata/history endpoint without cached authority')
        report={'stage':'A43.29','status':'PASS','checks':checks,'snapshottedTables':7,'notificationRequests':len(calls),'intentionalFixtureWrites':['profile-update-to-test-audit-binding','disable-owner-to-test-revocation'],
            'decisions':policy['decisions'],'realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(list(processes.values())):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for handle in handles:handle.close()
        if postgres:command('docker','rm','-f',postgres)
        probe.shutdown();probe.server_close();thread.join(timeout=5)

if __name__=='__main__':main()

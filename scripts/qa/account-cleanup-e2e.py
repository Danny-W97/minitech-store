#!/usr/bin/env python3
"""A43.34: isolated real account/domain cleanup with disposable PostgreSQL and Redis."""
import base64,hashlib,hmac,importlib.util,json,os,secrets,subprocess,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import quality_gates as qa

spec=importlib.util.spec_from_file_location('privacy_helpers',Path(__file__).with_name('security-data-export-e2e.py'))
helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
ROOT=helpers.ROOT;command=helpers.command;port=helpers.port

def main():
    directory=ROOT/'tmp'/('account-cleanup-'+secrets.token_hex(4));directory.mkdir(parents=True)
    from privacy_journal_fixture import JournalFixture
    journal_fixture=JournalFixture(directory)
    names=('customer-service','api-gateway','wishlist-service','recommendation-service','review-service','chat-service')
    urls={name:f'http://127.0.0.1:{port()}' for name in names}
    credentials={name:secrets.token_urlsafe(32) for name in names};operator=secrets.token_urlsafe(32)
    password=secrets.token_urlsafe(32);signing_bytes=secrets.token_bytes(48);signing=base64.b64encode(signing_bytes).decode()
    databases={name:name.removesuffix('-service')+'_db' for name in names if name!='api-gateway'}
    postgres=redis=None;processes={};handles=[];servers=[];threads=[];checks=[];peer_calls=[];billing_up=False;enabled=False
    def sql(database,query,allow_failure=False):
        result=subprocess.run(['docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d',database,'-Atqc',query],capture_output=True,text=True)
        if not allow_failure and result.returncode:raise RuntimeError('Isolated fixture SQL failed: '+result.stderr[-1200:])
        return result if allow_failure else result.stdout.strip()
    def cache(*args):return command('docker','exec',redis,'redis-cli','--raw',*map(str,args))
    def serve(handler):
        server=ThreadingHTTPServer(('127.0.0.1',0),handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        servers.append(server);threads.append(thread);return f'http://127.0.0.1:{server.server_port}'
    def handler(service):
        class Peer(BaseHTTPRequestHandler):
            def do_POST(self):
                body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))));assert self.headers.get('Authorization')=='Bearer '+credentials['customer-service']
                if self.path=='/notifications':self.send_response(201);self.end_headers();return
                assert self.path.startswith('/internal/privacy/fences/'),'unexpected business/provider operation';peer_calls.append((service,self.path))
                status=503 if service=='billing-service' and not billing_up else 200
                raw=json.dumps({'schemaVersion':1,'service':service,'customerId':self.path.rsplit('/',1)[1],'requestId':body['requestId'],'state':'BLOCKED'}).encode()
                self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
            def log_message(self,*args):pass
        return Peer
    peers={name:serve(handler(name)) for name in ('product-service','order-service','inventory-service','payment-service','notification-service','delivery-service','promotion-service','returns-service','billing-service')}
    def start(name):
        env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{databases.get(name,"customer_db")}',DB_USERNAME='fixture',DB_PASSWORD=password,
            REDIS_URL=f'redis://127.0.0.1:{redis_port}',CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=urls['customer-service'],PLATFORM_OPERATOR_TOKEN=operator,
            PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),CUSTOMER_JWT_SECRET=signing,CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(b'F'*32).decode(),
            PRIVACY_ACCOUNT_CLEANUP_ENABLED=str(enabled).lower(),PRIVACY_ACCOUNT_CLEANUP_INTERVAL_MS='400',PRIVACY_ACCOUNT_CLEANUP_INITIAL_DELAY_MS='400',
            CUSTOMER_PRIVACY_WORKER_INTERVAL_MS='400',CUSTOMER_PRIVACY_WORKER_INITIAL_DELAY_MS='400',CUSTOMER_SECURITY_RETENTION_ENABLED='false',CUSTOMER_AUDIT_RETENTION_MODE='off',
            CUSTOMER_EXPORT_CLEANUP_ENABLED='false',GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',CHAT_OUTBOX_ENABLED='false',
            RECOMMENDATION_REQUEST_LOG_CLEANUP_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
        env.update(journal_fixture.environment())
        env.update({name.removesuffix('-service').upper()+'_SERVICE_URL':value for name,value in {**peers,**urls}.items() if name not in ('api-gateway','customer-service')})
        jar=next((ROOT/name/'target').glob('*-SNAPSHOT.jar'));handle=(directory/(name+'-'+str(len(handles))+'.log')).open('w');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms48m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(jar)],env=env,cwd=ROOT,stdout=handle,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(name+' exited; see fixture logs '+str(directory))
            if qa.Client(urls[name],timeout=2).get('/actuator/health').status==200:return
            time.sleep(.3)
        raise RuntimeError(name+' startup timeout')
    def stop(name):
        p=processes[name]
        if p.poll() is None:
            p.terminate()
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=10)
    def until(predicate,label,seconds=90):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            result=predicate()
            if result:return result
            time.sleep(.3)
        raise RuntimeError('Timeout: '+label)
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine');db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        until(lambda:subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','fixture'],capture_output=True).returncode==0,'fixture PostgreSQL')
        for db in databases.values():command('docker','exec',postgres,'createdb','-U','fixture',db)
        redis=command('docker','run','-d','--rm','-p','127.0.0.1::6379','redis:7-alpine');redis_port=command('docker','port',redis,'6379').rsplit(':',1)[1]
        for name in names:start(name)
        customer=qa.Client(urls['customer-service']);gateway=qa.Client(urls['api-gateway']);private_password='FixturePrivacy1!';sessions=[]
        profile={'firstName':'Jan','lastName':'Fixture','street':'Testowa 1','postalCode':'00-001','city':'Test','country':'PL'}
        for email in ('owner@fixture.example','other@fixture.example','legacy@fixture.example'):
            qa.status(customer.get('/customers/register',method='POST',body={**profile,'email':email,'password':private_password}),201,'fixture registration')
            sql('customer_db',f"UPDATE customers SET enabled=true,verification_token_hash=NULL,verification_expires_at=NULL WHERE email='{email}'")
            response=qa.status(customer.get('/customers/login',method='POST',body={'email':email,'password':private_password}),200,'real login')
            sessions.append((response.document()['accessToken'],response.headers['set-cookie'].split(';',1)[0]))
        owner={'Authorization':'Bearer '+sessions[0][0]};other={'Authorization':'Bearer '+sessions[1][0]};coordinator={'Authorization':'Bearer '+credentials['customer-service']}
        sql('wishlist_db','INSERT INTO wishlist_items(customer_id,product_id,created_at) SELECT 1,n,now() FROM generate_series(1,205)n; INSERT INTO wishlist_items(customer_id,product_id,created_at) VALUES(2,999,now())')
        sql('recommendation_db',"INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at) SELECT 1,1,'FIXTURE',2,now() FROM generate_series(1,205)n; INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at) VALUES(2,1,'OTHER',2,now()),(NULL,1,'ANONYMOUS',2,now())")
        sql('review_db',"INSERT INTO reviews(customer_id,order_id,product_id,rating,title,content,status,moderation_note,created_at,updated_at) SELECT 1,n,101,5,'Private title','Private content','PUBLISHED','Private note',now(),now() FROM generate_series(1,205)n; INSERT INTO reviews(customer_id,order_id,product_id,rating,title,content,status,created_at,updated_at) VALUES(2,999,101,4,'Other','Other','PUBLISHED',now(),now())")
        qa.status(customer.get('/customers/mfa/totp/enroll',method='POST',headers=owner),200,'real encrypted pending TOTP fixture')
        sql('customer_db',"INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at) SELECT 1,'recovery-'||n,now() FROM generate_series(1,205)n; INSERT INTO password_reset_tokens(customer_id,token_hash,expires_at,created_at) VALUES(1,'reset-private',now()+interval '1 hour',now())")
        # Actual credential rows; the fixture issuer signs the matching AMR assertion.
        sql('customer_db',"INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,public_key_credential_type,backup_state,created) VALUES('canonical','MQ',decode('01','hex'),0,true,false,'public-key',false,now()),('padded','MQ==',decode('01','hex'),0,true,false,'public-key',false,now()),('other','Mg',decode('01','hex'),0,true,false,'public-key',false,now())")
        encoded=lambda value:base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=').decode()
        parts=sessions[0][0].split('.');claims=json.loads(base64.urlsafe_b64decode(parts[1]+'==='));claims['amr']=['pwd','webauthn'];unsigned=parts[0]+'.'+encoded(claims)
        owner={'Authorization':'Bearer '+unsigned+'.'+base64.urlsafe_b64encode(hmac.new(signing_bytes,unsigned.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()}
        chat_token=secrets.token_urlsafe(32)
        chat_id=qa.status(qa.Client(urls['chat-service']).get('/chat/messages',method='POST',headers={'X-Chat-Access-Token':chat_token},body={'customerName':'Jan','customerEmail':'owner@fixture.example','subject':'Private chat','message':'Other people may be mentioned'}),201,'separate conversation fixture').document()['id']
        other_chat_token=secrets.token_urlsafe(32)
        other_chat_id=qa.status(qa.Client(urls['chat-service']).get('/chat/messages',method='POST',headers={'X-Chat-Access-Token':other_chat_token},body={'customerName':'Jan','customerEmail':'owner@fixture.example','subject':'Another private chat','message':'Other conversation'}),201,'same email independent conversation').document()['id']
        chat_snapshot=sql('chat_db','SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM chat_messages c),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o ORDER BY entry_id) FROM chat_notification_outbox o))::text')
        def confirm(headers):
            preview=qa.status(customer.get('/customers/me/privacy-requests/preview',method='POST',headers={**headers,'Idempotency-Key':str(uuid.uuid4())}),200,'reviewed preview').document();token=secrets.token_urlsafe(32)
            qa.status(customer.get('/customers/me/privacy-requests/'+preview['requestId']+'/confirm',method='POST',headers=headers,body={'password':private_password,'statusToken':token,'planHash':preview['planHash'],'confirmation':'DELETE_MY_ACCOUNT'}),202,'explicit confirmation')
            return preview['requestId'],token
        request_id,status_token=confirm(owner);legacy_id,legacy_token=confirm({'Authorization':'Bearer '+sessions[2][0]})
        sql('customer_db',f"UPDATE account_privacy_requests SET account_cleanup_policy_hash=NULL WHERE id='{legacy_id}'")
        proof={'X-Privacy-Status-Token':status_token};path='/customers/privacy-requests/'+request_id+'/status'
        state=lambda:qa.status(customer.get(path,headers=proof),200,'purpose-bound status').document()
        until(lambda:sum(r['fence_state']=='FENCED' for r in state()['services'])==13,'thirteen fences')
        for name in databases:
            qa.status(qa.Client(urls[name]).get('/internal/privacy/account-cleanup/1',method='POST',headers=coordinator,body={'requestId':request_id}),409,'disabled by default')
        assert sql('customer_db','SELECT count(*) FROM customers WHERE id=1')=='1' and sql('review_db','SELECT count(*) FROM reviews WHERE customer_id=1')=='205'
        checks.append('default-off erasure, bound plan, real password/MFA and all-fourteen-fence prerequisite')
        cache('SET','customer:mfa:enrollment:1','fixture-private','EX',600);cache('SET','customer:mfa:webauthn:registration:1','fixture-private','EX',300)
        cache('SET','customer:mfa:login:fixture-unused','1','EX',300);cache('SET','customer:mfa:webauthn:login:fixture-unused','1|fixture-challenge','EX',300)
        cache('SET','security:login:lock:owner@fixture.example','1','EX',900);lock_ttl=int(cache('TTL','security:login:lock:owner@fixture.example'))
        journal_fixture.acknowledge(urls['customer-service'],operator)
        coordinator.update(journal_fixture.header(1))
        enabled=True
        for name in databases:stop(name);start(name)
        time.sleep(1)
        assert sql('customer_db','SELECT count(*) FROM customers WHERE id=1')=='1' and not state()['accountCleanup']['coreComplete']
        for name in databases:
            client=qa.Client(urls[name]);url='/internal/privacy/account-cleanup/1'
            for identity in (operator,credentials['chat-service'],sessions[1][0]):assert client.get(url,method='POST',headers={'Authorization':'Bearer '+identity},body={'requestId':request_id}).status in (401,403)
            qa.status(client.get(url,method='POST',headers=coordinator,body={'requestId':str(uuid.uuid4())}),409,'wrong request cannot mutate')
        stop('review-service');billing_up=True
        until(lambda:state()['phase']=='WAITING_ADAPTERS','fourteen fences')
        until(lambda:sql('customer_db','SELECT count(*) FROM customers WHERE id=1')=='0','owned profile removal')
        assert not state()['accountCleanup']['coreComplete'] and any(r['service']=='review-service' and r['cleanup_state']=='FAILED' for r in state()['services'])
        checks.append('service-only local adapters reject missing authority; unavailable Review is explicit partial state, never full success')
        sql('review_db',"CREATE FUNCTION fixture_abort_review_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rollback'; END $$; CREATE TRIGGER fixture_abort_review_delete AFTER DELETE ON reviews FOR EACH ROW EXECUTE FUNCTION fixture_abort_review_delete()")
        stop('customer-service');start('customer-service');start('review-service')
        qa.status(qa.Client(urls['review-service']).get('/internal/privacy/account-cleanup/1',method='POST',headers=coordinator,body={'requestId':request_id}),500,'SQL fault rolls back both review deletion and key retention')
        assert sql('review_db','SELECT count(*) FROM reviews WHERE customer_id=1')=='205' and sql('review_db','SELECT count(*) FROM review_privacy_keys')=='0'
        sql('review_db','DROP TRIGGER fixture_abort_review_delete ON reviews; DROP FUNCTION fixture_abort_review_delete()')
        until(lambda:state()['accountCleanup']['coreComplete'],'restart and failed-peer recovery',120)
        current=state();assert not current['complete'] and not current['dataErased']
        assert any(r['service']=='chat-service' and r['cleanup_state']=='SEPARATE_PROOF_REQUIRED' for r in current['services'])
        assert sql('wishlist_db','SELECT count(*) FROM wishlist_items WHERE customer_id=1')=='0' and sql('wishlist_db','SELECT count(*) FROM wishlist_items WHERE customer_id=2')=='1'
        assert sql('recommendation_db','SELECT count(*) FROM recommendation_requests WHERE customer_id=1')=='0' and sql('recommendation_db','SELECT count(*) FROM recommendation_requests')=='2'
        assert sql('review_db','SELECT count(*) FROM reviews WHERE customer_id=1')=='0' and sql('review_db','SELECT count(*) FROM review_privacy_keys WHERE customer_id=1')=='205'
        summary=qa.status(qa.Client(urls['review-service']).get('/reviews/products/101/summary'),200,'published rating summary').document();assert summary['publishedCount']==1 and summary['averageRating']==4
        for table in ('refresh_tokens','password_reset_tokens','customer_totp_methods','customer_recovery_codes'):assert sql('customer_db',f'SELECT count(*) FROM {table} WHERE customer_id=1')=='0'
        assert sql('customer_db',"SELECT count(*) FROM user_credentials WHERE user_entity_user_id IN ('MQ','MQ==')")=='0' and sql('customer_db',"SELECT count(*) FROM user_credentials WHERE user_entity_user_id='Mg'")=='1'
        assert sql('chat_db','SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM chat_messages c),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o ORDER BY entry_id) FROM chat_notification_outbox o))::text')==chat_snapshot
        assert sql('customer_db','SELECT count(*) FROM customers WHERE id IN (2,3)')=='2'
        legacy=qa.status(customer.get('/customers/privacy-requests/'+legacy_id+'/status',headers={'X-Privacy-Status-Token':legacy_token}),200,'legacy request').document();assert not legacy['accountCleanup']['policyReviewed'] and not legacy['accountCleanup']['coreComplete']
        checks.append('bounded 205-row domain removal, atomic review identity retention, no rating/publication, other/anonymous accounts intact and legacy policy never auto-approved')
        # In a rollback-only fixture transaction isolate the new dedup trigger from the older fence trigger.
        rejected=sql('review_db',"BEGIN; DELETE FROM account_privacy_fences WHERE customer_id=1; INSERT INTO reviews(customer_id,order_id,product_id,rating,title,content,status,created_at,updated_at) VALUES(1,1,101,5,'late','late','PUBLISHED',now(),now()); ROLLBACK",True)
        assert rejected.returncode and 'Review privacy deduplication key retained' in rejected.stderr and sql('review_db','SELECT count(*) FROM account_privacy_fences WHERE customer_id=1')=='1'
        assert cache('EXISTS','customer:mfa:enrollment:1','customer:mfa:webauthn:registration:1')=='0'
        assert cache('GET','customer:mfa:login:fixture-unused')=='1' and cache('GET','customer:mfa:webauthn:login:fixture-unused')=='1|fixture-challenge'
        assert 0<int(cache('TTL','security:login:lock:owner@fixture.example'))<=lock_ttl
        qa.status(customer.get('/customers/me/data-export',headers={'Authorization':'Bearer '+sessions[0][0]}),401,'old access token')
        qa.status(customer.get('/customers/refresh',method='POST',headers={'Cookie':sessions[0][1]}),401,'old refresh')
        cache('SET','customer:mfa:login:fixture-consumed','1','EX',300)
        qa.status(customer.get('/customers/mfa/login/verify',method='POST',body={'challengeId':'fixture-consumed','code':'000000'}),401,'old challenge cannot authenticate absent identity')
        checks.append('ID-only Redis cleanup, ineffective old JWT/refresh/challenge, unchanged random TTL and email lock, durable non-resurrection and SQL dedup trigger')
        qa.status(customer.get('/customers/register',method='POST',body={**profile,'email':'owner@fixture.example','password':private_password}),201,'same email may register a fresh identity')
        new_id=sql('customer_db',"SELECT id FROM customers WHERE email='owner@fixture.example'");assert new_id not in ('1','2','3')
        sql('customer_db',f'UPDATE customers SET enabled=true WHERE id={new_id}');cache('DEL','security:login:lock:owner@fixture.example')
        fresh=qa.status(customer.get('/customers/login',method='POST',body={'email':'owner@fixture.example','password':private_password}),200,'fresh login').document()['accessToken']
        items=qa.status(qa.Client(urls['wishlist-service']).get('/wishlists/customer/'+new_id,headers={'Authorization':'Bearer '+fresh}),200,'fresh account owns no old wishlist').document();assert not items
        for prefix in ('/api','/api/v1'):qa.status(gateway.get(prefix+path,headers=proof),200,'Gateway retains status after profile removal')
        checks.append('fresh ID registration without inherited data and both Gateway prefixes keep the separate safe request status')
        chat=qa.Client(urls['chat-service']);chat_path=f'/chat/conversations/{chat_id}/privacy-request';chat_proof={'X-Chat-Access-Token':chat_token};body={'confirmation':'REQUEST_CHAT_DELETION_REVIEW'}
        qa.status(chat.get(chat_path,headers=chat_proof),404,'no request before explicit intent')
        qa.status(chat.get(chat_path,method='POST',headers={**chat_proof,'Idempotency-Key':str(uuid.uuid4())},body={}),400,'explicit chat phrase required')
        qa.status(chat.get(chat_path,method='POST',headers=chat_proof,body=body),400,'chat request idempotency key required')
        from concurrent.futures import ThreadPoolExecutor
        def chat_submit(_):return qa.status(gateway.get('/api/v1'+chat_path,method='POST',headers={**chat_proof,'Idempotency-Key':str(uuid.uuid4())},body=body),202,'concurrent manual chat intent').document()
        with ThreadPoolExecutor(max_workers=2) as pool:receipts=list(pool.map(chat_submit,range(2)))
        assert receipts[0]==receipts[1] and receipts[0]['state']=='MANUAL_REVIEW' and not receipts[0]['contentErased'] and not receipts[0]['complete']
        assert sql('chat_db','SELECT count(*) FROM chat_privacy_requests')=='1'
        for prefix in ('/api','/api/v1'):
            receipt=qa.status(gateway.get(prefix+chat_path,headers=chat_proof),200,'private chat status through both prefixes');assert 'no-store' in receipt.headers['cache-control'] and receipt.headers['x-content-type-options']=='nosniff'
        for headers in ({},{'Authorization':'Bearer '+fresh}):qa.status(chat.get(chat_path,headers=headers),401,'account email never replaces chat token')
        qa.status(chat.get(chat_path,headers={'X-Chat-Access-Token':other_chat_token}),404,'same email wrong conversation token')
        qa.status(chat.get(f'/chat/conversations/{other_chat_id}/privacy-request',headers=chat_proof),404,'token bound to one conversation')
        qa.status(chat.get(f'/chat/conversations/{other_chat_id}/privacy-request',method='POST',headers={'X-Chat-Access-Token':other_chat_token,'Idempotency-Key':receipts[0]['requestId']},body=body),409,'request key cannot be rebound')
        for identity in (operator,credentials['customer-service']):
            qa.status(chat.get(chat_path,headers={**chat_proof,'Authorization':'Bearer '+identity}),403,'no operator/service bypass of chat request proof')
            qa.status(chat.get(chat_path,method='POST',headers={**chat_proof,'Authorization':'Bearer '+identity,'Idempotency-Key':str(uuid.uuid4())},body=body),403,'no privileged account-to-chat intent')
        assert sql('chat_db','SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM chat_messages c),(SELECT json_agg(e ORDER BY id) FROM chat_entries e),(SELECT json_agg(o ORDER BY entry_id) FROM chat_notification_outbox o))::text')==chat_snapshot
        saved=sql('chat_db','SELECT json_agg(r)::text FROM chat_privacy_requests r');encoded=json.dumps(receipts)
        for private in (chat_token,other_chat_token,'owner@fixture.example','Private chat','Other people may be mentioned'):assert private not in saved and private not in encoded
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'));qa.check_document(chat.get('/v3/api-docs').document(),contract['services']['chat'],'Chat')
        checks.append('independent capability-only manual Chat intent/status: parallel replay, explicit phrase, bound key, same-email isolation, role denials, no text/token persistence and unchanged conversation/entries/outbox')
        result={'stage':'A43.34','status':'PASS','checks':checks,'realApplications':list(names),'realDatabases':5,'realRedis':True,'simulatedPeers':9,'seededRowsPerDomain':205,'reviewKeysRetained':205,'fullErasureComplete':False,'realUserDataAccessed':False,'deployment':False,'fixtureActivationOnly':True}
        (directory/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for name in reversed(list(processes)):stop(name)
        for handle in handles:handle.close()
        for container in (redis,postgres):
            if container:command('docker','rm','-f',container)
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join(timeout=5)

if __name__=='__main__':main()

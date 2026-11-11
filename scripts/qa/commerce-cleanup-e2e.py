#!/usr/bin/env python3
"""A43.35: isolated real commerce workflow gates with disposable PostgreSQL and Redis."""
import base64,importlib.util,json,os,secrets,socket,subprocess,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import quality_gates as qa

spec=importlib.util.spec_from_file_location('privacy_helpers',Path(__file__).with_name('security-data-export-e2e.py'))
helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
ROOT=helpers.ROOT;command=helpers.command

def main():
    directory=ROOT/'tmp'/('commerce-cleanup-'+secrets.token_hex(4));directory.mkdir(parents=True)
    from privacy_journal_fixture import JournalFixture
    journal_fixture=JournalFixture(directory)
    names=('customer-service','api-gateway','order-service','inventory-service','promotion-service','delivery-service','returns-service')
    # Avoid outbound HTTP ephemeral ports (including TIME_WAIT) stealing a future
    # application's port while the earlier applications are starting.
    high=min(30000,int(Path('/proc/sys/net/ipv4/ip_local_port_range').read_text().split()[0])-1)
    low=10000 if high>10000 else 1024
    reservations={}
    for name in names:
        for _ in range(100):
            held=socket.socket()
            try:held.bind(('127.0.0.1',low+secrets.randbelow(high-low+1)));held.listen();break
            except OSError:held.close()
        else:raise RuntimeError('No isolated application fixture port available')
        reservations[name]=held
    urls={name:f'http://127.0.0.1:{held.getsockname()[1]}' for name,held in reservations.items()}
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
    peers={name:serve(handler(name)) for name in ('product-service','payment-service','notification-service','wishlist-service','recommendation-service','review-service','chat-service','billing-service')}
    def start(name):
        held=reservations.pop(name,None)
        if held:held.close()
        env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{databases.get(name,"customer_db")}',DB_USERNAME='fixture',DB_PASSWORD=password,
            REDIS_URL=f'redis://127.0.0.1:{redis_port}',CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=urls['customer-service'],PLATFORM_OPERATOR_TOKEN=operator,
            PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),CUSTOMER_JWT_SECRET=signing,CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(b'F'*32).decode(),
            PRIVACY_COMMERCE_CLEANUP_ENABLED=str(enabled).lower(),PRIVACY_COMMERCE_CLEANUP_INTERVAL_MS='400',PRIVACY_COMMERCE_CLEANUP_INITIAL_DELAY_MS='400',ORDER_SAGA_RECOVERY_ENABLED='false',COUPON_EXPIRY_ENABLED='false',DELIVERY_SYNC_ENABLED='false',PRIVACY_ACCOUNT_CLEANUP_ENABLED='false',PRIVACY_ACCOUNT_CLEANUP_INTERVAL_MS='400',PRIVACY_ACCOUNT_CLEANUP_INITIAL_DELAY_MS='400',
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
            response=qa.status(customer.get('/customers/login',method='POST',body={'email':email,'password':private_password}),200,'real login');sessions.append(response.document()['accessToken'])
        owner={'Authorization':'Bearer '+sessions[0]};coordinator={'Authorization':'Bearer '+credentials['customer-service']}
        for subject in (1,2,3):
            sql('order_db',f"INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at) VALUES({subject},{subject},'owner@fixture.example',123.45,'CANCELLED','Jan','Fixture','Testowa','00-001','Test','PL','STANDARD_COURIER',0,0,'HOME_DELIVERY','fixture-{subject}',repeat('a',64),'customer:{subject}',now(),now()); INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES({subject},'FAILED',now(),now())")
        sql('returns_db',"INSERT INTO return_requests(order_id,customer_id,customer_email,request_type,reason,description,status,request_hash,order_verified_at,created_at,updated_at) VALUES(1,1,'owner@fixture.example','RETURN','Reason','Description','REJECTED',repeat('a',64),now(),now(),now())")
        def confirm(identity):
            headers={'Authorization':'Bearer '+identity};preview=qa.status(customer.get('/customers/me/privacy-requests/preview',method='POST',headers={**headers,'Idempotency-Key':str(uuid.uuid4())}),200,'reviewed preview').document();token=secrets.token_urlsafe(32)
            assert any('tracking' in effect for effect in preview['effects'])
            qa.status(customer.get('/customers/me/privacy-requests/'+preview['requestId']+'/confirm',method='POST',headers=headers,body={'password':private_password,'statusToken':token,'planHash':preview['planHash'],'confirmation':'DELETE_MY_ACCOUNT'}),202,'explicit confirmation');return preview['requestId'],token
        request_id,status_token=confirm(sessions[0]);legacy_id,legacy_token=confirm(sessions[2]);sql('customer_db',f"UPDATE account_privacy_requests SET commerce_cleanup_policy_hash=NULL WHERE id='{legacy_id}'")
        proof={'X-Privacy-Status-Token':status_token};path='/customers/privacy-requests/'+request_id+'/status';state=lambda:qa.status(customer.get(path,headers=proof),200,'safe status').document()
        until(lambda:sum(row['fence_state']=='FENCED' for row in state()['services'])==13,'unavailable peer blocks cleanup')
        for name in databases:
            if name=='customer-service':continue
            qa.status(qa.Client(urls[name]).get('/internal/privacy/commerce-cleanup/1',method='POST',headers=coordinator,body={'requestId':request_id,'cursor':0}),409,'default-off domain cleanup')
        assert sql('order_db','SELECT privacy_redacted FROM orders WHERE id=1')=='f'
        checks.append('default-off five adapters and fourteen-fence requirement, current password and explicit reviewed policy')
        journal_fixture.acknowledge(urls['customer-service'],operator)
        coordinator.update(journal_fixture.header(1))
        enabled=True
        # Keep the coordinator disabled until every target adapter has restarted.
        for name in reversed(databases):stop(name);start(name)
        billing_up=True
        for pending in (request_id,legacy_id):
            until(lambda:customer.get('/customers/privacy-requests/'+pending+'/retry',method='POST',headers={'Authorization':'Bearer '+operator}).status==200,'resume exhausted fixture peer')
        until(lambda:state()['phase']=='WAITING_ADAPTERS','fourteen fences')
        until(lambda:sum(row['cleanup_state']=='WAITING_FINANCE' for row in state()['services'])==4,'four real domain adapters require A43.36',120)
        current=state();assert current['commerceCleanup']['financeHandoffPending'] and current['commerceCleanup']['policyReviewed'] and not current['complete'] and not current['dataErased']
        assert any(row['service']=='order-service' and row['cleanup_state']=='PENDING' for row in current['services'])
        source_client=qa.Client(urls['order-service']);source_path='/internal/privacy/commerce-orders/1';body={'requestId':request_id,'cursor':0}
        for name in databases:
            if name=='customer-service':continue
            client=qa.Client(urls[name]);url='/internal/privacy/commerce-cleanup/1'
            for identity in (operator,credentials['payment-service'] if 'payment-service' in credentials else credentials['order-service'],sessions[1]):assert client.get(url,method='POST',headers={'Authorization':'Bearer '+identity},body=body).status in (401,403)
            qa.status(client.get(url,method='POST',headers=coordinator,body={**body,'requestId':str(uuid.uuid4())}),409,'foreign request denied')
        raw=qa.status(source_client.get(source_path,method='POST',headers=coordinator,body=body),200,'private canonical metadata').document();assert raw['order']['orderId']=='1' and not raw['order']['financeCleared']
        assert 'owner@fixture.example' not in json.dumps(raw) and 'Testowa' not in json.dumps(raw)
        for identity in (operator,sessions[1],credentials['order-service']):qa.status(source_client.get(source_path,method='POST',headers={'Authorization':'Bearer '+identity},body=body),403,'canonical metadata rejects privileged or wrong service roles')
        checks.append('real canonical Order graph, safe service-only metadata, foreign requests denied, real finance gate never bypassed')
        snapshot=sql('order_db','SELECT json_agg(o ORDER BY id)::text FROM orders o')
        for name,db in databases.items():
            if name!='customer-service':assert sql(db,'SELECT count(*) FROM commerce_privacy_locks')=='0'
        assert sql('order_db','SELECT customer_email FROM orders WHERE id=1')=='owner@fixture.example'
        assert sql('customer_db','SELECT count(*) FROM customers')=='3'
        stop('customer-service');start('customer-service');stop('order-service');start('order-service')
        current=state();assert current['commerceCleanup']['financeHandoffPending'];assert sql('order_db','SELECT json_agg(o ORDER BY id)::text FROM orders o')==snapshot
        legacy=qa.status(customer.get('/customers/privacy-requests/'+legacy_id+'/status',headers={'X-Privacy-Status-Token':legacy_token}),200,'historical policy').document();assert not legacy['commerceCleanup']['policyReviewed']
        for prefix in ('/api','/api/v1'):qa.status(gateway.get(prefix+path,headers=proof),200,'Gateway safe status')
        qa.status(customer.get('/customers/privacy-requests/'+request_id+'/retry',method='POST',headers={'Authorization':'Bearer '+operator}),200,'operator retry reschedules but does not approve finance')
        until(lambda:sum(row['cleanup_state']=='WAITING_FINANCE' for row in state()['services'])==4,'retry retains finance gate')
        checks.append('restart preserves waiting/cursor/policy and source contact; operator retry cannot approve finance; other accounts untouched, both Gateway prefixes work')
        # Exercise active domain states through actual private adapters, without any financial bypass.
        sql('inventory_db',"INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES(20,1,'RESERVED',now(),now(),now()+interval '1 hour')")
        assert qa.status(qa.Client(urls['inventory-service']).get('/internal/privacy/commerce-cleanup/1',method='POST',headers=coordinator,body=body),200,'active reservation').document()['code']=='WAITING_RESERVATION'
        sql('inventory_db',"UPDATE inventory_reservations SET status='RELEASED' WHERE id=20")
        sql('delivery_db',"INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,sync_state,created_at,updated_at) VALUES(1,'KEEP-TRACKING','Carrier','COURIER','IN_TRANSIT','PENDING',now(),now())")
        assert qa.status(qa.Client(urls['delivery-service']).get('/internal/privacy/commerce-cleanup/1',method='POST',headers=coordinator,body=body),200,'active shipment').document()['code']=='WAITING_SHIPMENT'
        sql('returns_db',"UPDATE return_requests SET status='APPROVED' WHERE order_id=1")
        assert qa.status(qa.Client(urls['returns-service']).get('/internal/privacy/commerce-cleanup/1',method='POST',headers=coordinator,body=body),200,'active return').document()['code']=='WAITING_RETURN_PROCESS'
        sql('order_db',"UPDATE orders SET status='PAID' WHERE id=1")
        assert qa.status(qa.Client(urls['promotion-service']).get('/internal/privacy/commerce-cleanup/1',method='POST',headers=coordinator,body=body),200,'active source workflow').document()['code']=='WAITING_ORDER_PROCESS'
        assert sql('inventory_db',"SELECT status FROM inventory_reservations WHERE id=20")=='RELEASED' and sql('delivery_db',"SELECT tracking_number FROM shipments WHERE order_id=1")=='KEEP-TRACKING'
        checks.append('active source workflow, reservation, shipment and return wait without cancelling, restoring stock, dropping tracking or clearing text')
        result={'stage':'A43.35','status':'PASS','checks':checks,'realApplications':list(names),'realDatabases':6,'realRedis':True,'simulatedPeers':8,'financeIntegrationPending':'A43.36','physicalRedactionProof':'separate constructor-injected PostgreSQL tests, no production bypass','fullErasureComplete':False,'realUserDataAccessed':False,'deployment':False,'fixtureActivationOnly':True}
        (directory/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for name in reversed(list(processes)):stop(name)
        for held in reservations.values():held.close()
        for handle in handles:handle.close()
        for container in (redis,postgres):
            if container:command('docker','rm','-f',container)
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join(timeout=5)

if __name__=='__main__':main()

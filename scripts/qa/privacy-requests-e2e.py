#!/usr/bin/env python3
"""A43.33: isolated Customer/Gateway/Wishlist/Order, PostgreSQL and Redis; no user stores."""
import concurrent.futures,importlib.util,json,os,secrets,subprocess,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import quality_gates as qa
import base64
spec=importlib.util.spec_from_file_location('helpers',Path(__file__).with_name('security-data-export-e2e.py'));helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
ROOT=helpers.ROOT;command=helpers.command;port=helpers.port

def main():
    directory=ROOT/'tmp'/('privacy-protocol-'+secrets.token_hex(4));directory.mkdir(parents=True)
    names=('customer-service','api-gateway','wishlist-service','order-service');urls={name:f'http://127.0.0.1:{port()}' for name in names}
    credentials={name:secrets.token_urlsafe(32) for name in names};operator=secrets.token_urlsafe(32);password=secrets.token_urlsafe(32)
    signing=base64.b64encode(secrets.token_bytes(48)).decode();mfa_key=base64.b64encode(secrets.token_bytes(32)).decode()
    postgres=redis=None;processes={};handles=[];servers=[];threads=[];checks=[];peer_calls=[];billing_up=False
    databases={'customer-service':'customer_db','wishlist-service':'wishlist_db','order-service':'order_db'}
    def sql(database,query,allow_failure=False):
        result=subprocess.run(['docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d',database,'-Atqc',query],capture_output=True,text=True)
        if not allow_failure and result.returncode:raise RuntimeError('Fixture SQL failed: '+result.stderr[-1000:])
        return result if allow_failure else result.stdout.strip()
    def serve(handler):
        server=ThreadingHTTPServer(('127.0.0.1',0),handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();servers.append(server);threads.append(thread);return f'http://127.0.0.1:{server.server_port}'
    def peer_handler(service):
        class Peer(BaseHTTPRequestHandler):
            def do_POST(self):
                size=int(self.headers.get('Content-Length','0'));body=json.loads(self.rfile.read(size))
                assert self.headers.get('Authorization')=='Bearer '+credentials['customer-service']
                if self.path=='/notifications':self.send_response(201);self.end_headers();return
                assert self.path.startswith('/internal/privacy/fences/');customer=self.path.rsplit('/',1)[1];assert customer.isdigit()
                peer_calls.append(service);status=200 if service!='billing-service' or billing_up else 503
                response=json.dumps({'schemaVersion':1,'service':service,'customerId':customer,'requestId':body['requestId'],'state':'BLOCKED'}).encode()
                self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(response)
            def log_message(self,*args):pass
        return Peer
    peer_urls={name:serve(peer_handler(name)) for name in ('product-service','inventory-service','payment-service','notification-service','delivery-service','promotion-service','review-service','recommendation-service','chat-service','returns-service','billing-service')}
    class StaleIdentityProbe(BaseHTTPRequestHandler):
        def do_POST(self):
            assert self.headers.get('X-Service-Token')==credentials['wishlist-service']
            body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            if body.get('token')=='fixture-admitted-before-revocation':doc={'role':'CUSTOMER','customerId':1,'email':'owner@fixture.example'}
            else:
                result=qa.status(qa.Client(urls['customer-service']).get('/internal/auth/introspect',method='POST',body=body,headers={'X-Service-Token':credentials['wishlist-service']}),200,'fixture issuer delegation');doc=result.document()
            raw=json.dumps(doc).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
        def log_message(self,*args):pass
    stale_auth=serve(StaleIdentityProbe)
    def start(name):
        env={key:value for key,value in os.environ.items() if key in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{databases.get(name,"customer_db")}',DB_USERNAME='fixture',DB_PASSWORD=password,
            REDIS_URL=f'redis://127.0.0.1:{redis_port}',CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=stale_auth if name=='wishlist-service' else urls['customer-service'],
            PLATFORM_OPERATOR_TOKEN=operator,PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),CUSTOMER_JWT_SECRET=signing,CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=mfa_key,
            CUSTOMER_SECURITY_RETENTION_ENABLED='false',CUSTOMER_AUDIT_RETENTION_MODE='off',CUSTOMER_EXPORT_CLEANUP_ENABLED='false',CUSTOMER_PRIVACY_WORKER_INTERVAL_MS='500',CUSTOMER_PRIVACY_WORKER_INITIAL_DELAY_MS='500',
            GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',ORDER_SAGA_POLL_INTERVAL_MS='3600000',ORDER_SAGA_INITIAL_DELAY_MS='3600000')
        all_urls={**peer_urls,**urls}
        env.update({key.removesuffix('-service').upper()+'_SERVICE_URL':value for key,value in all_urls.items() if key not in ('api-gateway','customer-service')})
        jars=list((ROOT/name/'target').glob('*-SNAPSHOT.jar'));assert len(jars)==1
        handle=(directory/(name+'-'+str(len(handles))+'.log')).open('w');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms48m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(jars[0])],env=env,cwd=ROOT,stdout=handle,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(f'{name} exited; fixture logs {directory}')
            if qa.Client(urls[name],timeout=2).get('/actuator/health').status==200:return
            time.sleep(.3)
        raise RuntimeError(f'{name} startup timed out')
    def stop(name):
        process=processes[name]
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine');db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','fixture'],capture_output=True).returncode==0:break
            time.sleep(.3)
        else:raise RuntimeError('PostgreSQL fixture startup timed out')
        for name in databases.values():command('docker','exec',postgres,'createdb','-U','fixture',name)
        redis=command('docker','run','-d','--rm','-p','127.0.0.1::6379','redis:7-alpine');redis_port=command('docker','port',redis,'6379').rsplit(':',1)[1]
        for name in names:start(name)
        customer=qa.Client(urls['customer-service']);gateway=qa.Client(urls['api-gateway']);wishlist=qa.Client(urls['wishlist-service'])
        private_password='FixturePrivacy1!'
        sessions=[]
        for email in ('owner@fixture.example','other@fixture.example'):
            payload={'email':email,'password':private_password,'firstName':'Jan','lastName':'Test','street':'Testowa 1','postalCode':'00-001','city':'Warszawa','country':'PL'}
            qa.status(customer.get('/customers/register',method='POST',body=payload),201,'fixture registration')
            sql('customer_db',f"UPDATE customers SET enabled=TRUE,verification_token_hash=NULL,verification_expires_at=NULL WHERE email='{email}'")
            response=qa.status(customer.get('/customers/login',method='POST',body={'email':email,'password':private_password}),200,'real fixture login')
            sessions.append((response.document()['accessToken'],response.headers['set-cookie'].split(';',1)[0]))
        owner={'Authorization':'Bearer '+sessions[0][0]};other={'Authorization':'Bearer '+sessions[1][0]}
        qa.status(wishlist.get('/wishlists/customer/1/products/101',method='PUT',headers=owner),201,'owned fixture wishlist insert')
        snapshot=sql('wishlist_db','SELECT json_agg(w ORDER BY id)::text FROM wishlist_items w')
        profile=sql('customer_db',"SELECT (to_jsonb(c)-ARRAY['enabled','verification_token_hash','verification_expires_at','updated_at'])::text FROM customers c WHERE id=1")
        base='/api/v1/customers/me/privacy-requests';key=str(uuid.uuid4())
        preview=qa.status(gateway.get(base+'/preview',method='POST',headers={**owner,'Idempotency-Key':key}),200,'own privacy preview').document()
        request_id=preview['requestId'];assert preview['phase']=='PREVIEW' and preview['ownerId']=='1' and len(preview['plannedScope'])==14 and not preview['dataErased']
        assert qa.status(gateway.get(base+'/preview',method='POST',headers={**owner,'Idempotency-Key':key}),200,'idempotent preview').document()['requestId']==request_id
        status_token=secrets.token_urlsafe(32);body={'planHash':preview['planHash'],'password':private_password,'statusToken':status_token,'confirmation':'DELETE_MY_ACCOUNT'}
        for change,code in [({'confirmation':'yes'},400),({'planHash':'b'*64},409),({'password':'wrong'},401)]:qa.status(gateway.get(base+'/'+request_id+'/confirm',method='POST',headers=owner,body={**body,**change}),code,'invalid confirmation has no effect')
        qa.status(gateway.get(base+'/'+request_id+'/confirm',method='POST',headers=other,body=body),404,'another owner cannot confirm')
        sql('customer_db',"INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,last_used_time_step,created_at,updated_at) VALUES(1,'fixture-only-private',true,0,now(),now())")
        qa.status(gateway.get(base+'/'+request_id+'/confirm',method='POST',headers={**owner,'X-MFA-Verified':'true'},body=body),403,'password-only JWT cannot bypass configured MFA')
        sql('customer_db','DELETE FROM customer_totp_methods WHERE customer_id=1')
        assert sql('customer_db','SELECT enabled FROM customers WHERE id=1')=='t' and sql('customer_db','SELECT count(*) FROM account_privacy_fences')=='0'
        checks.append('real registration/login; owner-bound idempotent preview, explicit phrase/password/plan/owner and configured MFA checks before any fence')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:gateway.get(base+'/'+request_id+'/confirm',method='POST',headers=owner,body=body),range(2)))
        assert sorted(r.status for r in results)==[202,401],[r.status for r in results]
        confirmed=next(r for r in results if r.status==202);assert 'Max-Age=0' in confirmed.headers['set-cookie']
        data=confirmed.document();assert data['phase']=='FENCING' and not data['complete'] and not data['dataErased']
        assert sql('customer_db','SELECT enabled FROM customers WHERE id=1')=='f'
        assert sql('customer_db','SELECT count(*) FROM refresh_tokens WHERE customer_id=1 AND revoked_at IS NULL')=='0'
        assert sql('customer_db',"SELECT (to_jsonb(c)-ARRAY['enabled','verification_token_hash','verification_expires_at','updated_at'])::text FROM customers c WHERE id=1")==profile
        qa.status(customer.get('/customers/login',method='POST',body={'email':'owner@fixture.example','password':private_password}),401,'disabled login')
        qa.status(customer.get('/customers/refresh',method='POST',headers={'Cookie':sessions[0][1]}),401,'refresh revoked')
        qa.status(customer.get('/customers/me/data-export',headers=owner),401,'access JWT revoked')
        qa.status(wishlist.get('/wishlists/customer/1',headers=owner),401,'downstream resolves revoked identity')
        qa.status(wishlist.get('/wishlists/customer/2',headers=other),200,'other owner unaffected')
        checks.append('parallel confirmation admits exactly one request; account/JWT/refresh blocked atomically; profile data and other owner remain intact')
        public='/customers/privacy-requests/'+request_id+'/status';proof={'X-Privacy-Status-Token':status_token}
        def state():return qa.status(customer.get(public,headers=proof),200,'capability-only privacy status').document()
        current=state();assert len(current['services'])==14 and current['phase']=='FENCING'
        for prefix in ('/api','/api/v1'):qa.status(gateway.get(prefix+public,headers=proof),200,'both Gateway prefixes forward status capability privately')
        encoded=json.dumps(current);assert all(value not in encoded for value in ('customerId','customer_id','status_token_hash','run_token','owner@fixture.example',status_token,private_password))
        for headers in ({},{'Authorization':'Bearer '+operator},{'Authorization':'Bearer '+credentials['customer-service']},other):qa.status(customer.get(public,headers=headers),401,'no role bypass of status capability')
        qa.status(customer.get(public,headers={'X-Privacy-Status-Token':secrets.token_urlsafe(32)}),404,'wrong status capability')
        qa.status(customer.get('/customers/me/data-export',headers={'Authorization':'Bearer '+status_token}),401,'status token cannot export account data')
        qa.status(customer.get('/customers/privacy-requests/'+request_id+'/retry',method='POST',headers=proof),401,'status token cannot mutate')
        checks.append('seven-day purpose-bound read-only status has safe per-service metadata, no secrets/account content and no operator/service/cross-account bypass')
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            current=state()
            if sum(row['fence_state']=='FENCED' for row in current['services'])==13:break
            time.sleep(.3)
        else:raise RuntimeError('Thirteen service acknowledgements did not arrive')
        assert current['phase']=='FENCING' and any(row['service']=='billing-service' and row['fence_state']!='FENCED' for row in current['services'])
        stale={'Authorization':'Bearer fixture-admitted-before-revocation'}
        result=wishlist.get('/wishlists/customer/1/products/101',method='DELETE',headers=stale)
        assert result.status>=400 and sql('wishlist_db','SELECT json_agg(w ORDER BY id)::text FROM wishlist_items w')==snapshot,'stale actor bypassed actual transaction guard'
        assert sql('customer_db','UPDATE customers SET enabled=TRUE WHERE id=1',True).returncode!=0
        for name in ('customer_db','wishlist_db','order_db'):assert sql(name,'SELECT count(*) FROM account_privacy_fences')=='1'
        checks.append('unavailable peer remains FENCING; real Wishlist transaction guard rejects a deliberately stale authenticated DELETE; three real databases persist ID-only fences')
        stop('customer-service');start('customer-service');assert state()['requestId']==request_id
        billing_up=True;deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            current=state()
            if current['phase']=='WAITING_ADAPTERS':break
            time.sleep(.3)
        else:raise RuntimeError('Privacy fencing did not resume after restart')
        assert all(row['fence_state']=='FENCED' for row in current['services']) and not current['complete'] and not current['dataErased']
        assert sql('customer_db','SELECT count(*) FROM account_privacy_requests')=='1' and sql('customer_db','SELECT count(*) FROM account_privacy_services')=='14'
        assert sql('wishlist_db','SELECT json_agg(w ORDER BY id)::text FROM wishlist_items w')==snapshot
        checks.append('real Customer restart, retry/backoff and idempotent peer acknowledgements reach WAITING_ADAPTERS without erasing data or claiming completion')
        op_path='/customers/privacy-requests/'+request_id+'/operator-status'
        qa.status(customer.get(op_path,headers={'Authorization':'Bearer '+operator}),200,'operator status')
        qa.status(customer.get(op_path,headers=other),403,'ordinary account cannot operate privacy requests')
        sql('customer_db',f"UPDATE account_privacy_requests SET confirmed_at=now()-interval '192 hours',status_expires_at=now()-interval '24 hours' WHERE id='{request_id}'")
        qa.status(customer.get(public,headers=proof),404,'capability expires independently of worker')
        qa.status(customer.get(op_path,headers={'Authorization':'Bearer '+operator}),200,'operator remains available after capability expiry')
        qa.status(customer.get('/customers/privacy-requests/'+request_id+'/retry',method='POST',headers={'Authorization':'Bearer '+operator}),200,'operator retry cannot remove fences')
        assert sql('customer_db','SELECT count(*) FROM account_privacy_fences')=='1'
        checks.append('hard status expiry, operator-only inspection/retry and durable marker retention without automatic TTL')
        for prefix in ('/api','/api/v1'):qa.status(gateway.get(prefix+public,headers={'X-Privacy-Status-Token':status_token}),404,'both Gateway prefixes enforce expired proof')
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'));qa.check_document(customer.get('/v3/api-docs').document(),contract['services']['customer'],'Customer')
        report={'stage':'A43.33','status':'PASS','checks':checks,'realApplications':list(names),'realDatabases':3,'realRedis':True,'simulatedPeers':11,'peerCalls':len(peer_calls),'databaseUserDataAccessed':False,'realUserDataAccessed':False,'deployment':False,'dataErased':False,'complete':False,'decisions':{'marker':'stable-id-only-no-email-or-email-hash','markerTtl':'pending-A43.40-no-auto-deletion','statusSeconds':604800}}
        path=directory/'report.json';path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'PASS','checks':len(checks),'report':str(path)}))
    finally:
        for name in reversed(list(processes)):stop(name)
        for handle in handles:handle.close()
        for container in (redis,postgres):
            if container:command('docker','rm','-f',container)
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join(timeout=5)

if __name__=='__main__':main()

#!/usr/bin/env python3
"""A43.36: isolated real finance, identity and technical-copy workflow with disposable PostgreSQL and Redis."""
import base64,importlib.util,json,os,secrets,socket,subprocess,threading,time,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import quality_gates as qa

spec=importlib.util.spec_from_file_location('privacy_helpers',Path(__file__).with_name('security-data-export-e2e.py'))
helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
ROOT=helpers.ROOT;command=helpers.command

def main():
    directory=ROOT/'tmp'/('finance-privacy-'+secrets.token_hex(4));directory.mkdir(parents=True)
    from privacy_journal_fixture import JournalFixture
    journal_fixture=JournalFixture(directory)
    names=('customer-service','api-gateway','order-service','inventory-service','promotion-service','delivery-service','returns-service','payment-service','billing-service','notification-service')
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
    postgres=redis=None;processes={};handles=[];servers=[];threads=[];checks=[];peer_calls=[];enabled=False;mailpit=None;legacy_readers=True
    legacy_root=Path(os.environ.get('A43_36_LEGACY_JARS',str(ROOT/'tmp/a43-36-legacy')))
    legacy_available=all((legacy_root/(name+'.jar')).is_file() for name in ('billing-service','notification-service'))
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
                status=200
                raw=json.dumps({'schemaVersion':1,'service':service,'customerId':self.path.rsplit('/',1)[1],'requestId':body['requestId'],'state':'BLOCKED'}).encode()
                self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
            def log_message(self,*args):pass
        return Peer
    peers={name:serve(handler(name)) for name in ('product-service','wishlist-service','recommendation-service','review-service','chat-service')}
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
        env.update(PRIVACY_FINANCE_CLEANUP_ENABLED=str(enabled).lower(),PRIVACY_FINANCE_CLEANUP_INTERVAL_MS='400',PRIVACY_FINANCE_CLEANUP_INITIAL_DELAY_MS='400',
            PAYMENT_FOLLOW_UPS_ENABLED='false',STRIPE_RECOVERY_ENABLED='false',OUTBOX_PUBLISHER_FIXED_DELAY_MS='400',BILLING_CONSUMER_FIXED_DELAY_MS='200',NOTIFICATION_CONSUMER_FIXED_DELAY_MS='200',EVENT_CONSUMER_CLAIM_IDLE_MS='1000',
            NOTIFICATION_RETRY_FIXED_DELAY_MS='200',MAIL_HOST='127.0.0.1',MAIL_PORT=smtp_port,BILLING_INVOICE_STREAM_RETENTION_REQUIRED_GROUPS='fixture-ledger')
        env.update(journal_fixture.environment())
        env.update({name.removesuffix('-service').upper()+'_SERVICE_URL':value for name,value in {**peers,**urls}.items() if name not in ('api-gateway','customer-service')})
        env.update(BILLING_CONSUMER_ENABLED=str(not legacy_readers or legacy_available).lower(),NOTIFICATION_CONSUMER_ENABLED=str(not legacy_readers or legacy_available).lower())
        jar=(legacy_root/(name+'.jar')) if legacy_available and legacy_readers and name in ('billing-service','notification-service') else next((ROOT/name/'target').glob('*-SNAPSHOT.jar'));handle=(directory/(name+'-'+str(time.time_ns())+'.log')).open('w');handles.append(handle)
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
    def post(name,path,body,identity=None,expected=200):
        headers={'Authorization':'Bearer '+(identity or credentials['customer-service'])}
        if enabled and path.startswith('/internal/privacy/finance-prepare/'):headers.update(journal_fixture.header(1))
        return qa.status(qa.Client(urls[name]).get(path,method='POST',headers=headers,body=body),expected,path).document()
    def event(order,kind='PAYMENT_SUCCEEDED',v2=False):
        import hashlib
        key=f'{kind}:{order}:owner@fixture.example';digest=hashlib.sha256(key.encode()).hexdigest()
        fields={'eventId':'payment-'+digest,'eventKey':key,'eventType':kind,'orderId':str(order),'amount':'123.45','recipient':'owner@fixture.example','deliveryAddress':'Testowa 1','correlationId':'fixture-correlation'}
        if v2:fields.update(protocolVersion='2',eventId='payment-v2-'+str(uuid.uuid4()),legacyEventId='payment-'+digest,eventKey='v2:'+digest)
        return fields
    def add(fields):return cache('XADD','payment-events','*',*[part for item in fields.items() for part in item])
    def outbox(order,v2=False):
        fields=event(order,v2=v2)
        sql('payment_db',f"INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,delivery_address,correlation_id,status,attempts,next_attempt_at,created_at,updated_at,stored_event_id,identity_version) VALUES('{fields['eventKey']}','PAYMENT_SUCCEEDED',{order},123.45,'owner@fixture.example','Testowa 1','fixture-correlation','PENDING',0,now(),now(),now(),"+("'"+fields['eventId']+"',2)" if v2 else 'NULL,1)'))
        return fields
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine');db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        until(lambda:subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','fixture'],capture_output=True).returncode==0,'fixture PostgreSQL')
        for db in databases.values():command('docker','exec',postgres,'createdb','-U','fixture',db)
        redis=command('docker','run','-d','--rm','-p','127.0.0.1::6379','redis:7-alpine');redis_port=command('docker','port',redis,'6379').rsplit(':',1)[1]
        mailpit=command('docker','run','-d','--rm','-p','127.0.0.1::1025','-p','127.0.0.1::8025','axllent/mailpit:v1.27.9');smtp_port=command('docker','port',mailpit,'1025').rsplit(':',1)[1]
        mail_url='http://127.0.0.1:'+command('docker','port',mailpit,'8025').rsplit(':',1)[1]
        def mails():return qa.status(qa.Client(mail_url).get('/api/v1/messages'),200,'own Mailpit').document()['total']
        from concurrent.futures import ThreadPoolExecutor
        start('customer-service')
        with ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(start,names[1:]))
        # The actual accepted A43.35 readers run first, with no privacy markers installed.
        first=outbox(1);blocked=outbox(10,True)
        if legacy_available:
            until(lambda:sql('notification_db',"SELECT count(*) FROM notifications WHERE source_id=1 AND event_type='PAYMENT_SUCCEEDED' AND status='SENT'")=='1','v1 delivery by previous reader')
            until(lambda:sql('billing_db',"SELECT count(*) FROM event_inbox WHERE related_id=1 AND status='ACCEPTED'")=='1','v1 accepted by previous reader')
        until(lambda:int(sql('payment_db','SELECT attempts FROM payment_outbox_events WHERE order_id=10'))>0,'v2 waits for previous readers')
        assert sql('payment_db','SELECT status FROM payment_outbox_events WHERE order_id=10')!='PUBLISHED'
        print('PASS reader capability fence',flush=True)
        checks.append(('actual A43.35 JAR readers accept unchanged v1 backlog' if legacy_available else 'disabled consumers keep v1 in stream')+'; v2 publisher waits for both capability endpoints')
        if legacy_available:
            # A legacy reset token with the same numeric source as the customer is not ownership evidence.
            sql('notification_db',"INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,attempt_count,created_at,updated_at,sent_at) VALUES('PASSWORD_RESET_REQUESTED',1,'legacy-reset@fixture.example','EMAIL','Legacy secret','Legacy token text','SENT',1,now(),now(),now()),('CHAT_REPLY_SENT',1,'chat@fixture.example','EMAIL','Chat','Chat text','SENT',1,now(),now(),now())")
        legacy_readers=False
        for name in ('billing-service','notification-service'):stop(name);start(name)
        until(lambda:sql('payment_db','SELECT status FROM payment_outbox_events WHERE order_id=10')=='PUBLISHED','v2 publication after reader upgrade',120)
        until(lambda:sql('notification_db',"SELECT count(*) FROM notifications WHERE source_id=10 AND status='SENT'")=='1','v2 delivery')
        until(lambda:sql('notification_db',"SELECT count(*) FROM notifications WHERE source_id=1 AND event_type='PAYMENT_SUCCEEDED' AND status='SENT'")=='1','v1 delivery after upgrade')
        before=mails();duplicate=event(1,v2=True);duplicate_record=add(duplicate)
        until(lambda:sql('notification_db',f"SELECT count(*) FROM event_inbox WHERE event_id='{duplicate['eventId']}'")=='1','dual receipt v1/v2')
        until(lambda:sql('billing_db',f"SELECT count(*) FROM event_inbox WHERE event_id='{duplicate['eventId']}'")=='1','Billing dual receipt')
        assert sql('notification_db',"SELECT count(*) FROM notifications WHERE source_id=1 AND event_type='PAYMENT_SUCCEEDED'")=='1'
        assert mails()==before
        # Explicit ACK-loss redelivery keeps the exact original canonical fingerprint.
        stop('billing-service');start('billing-service');add(first)
        until(lambda:cache('XPENDING','payment-events','billing-service').splitlines()[0]=='0','restart ACK recovery')
        assert sql('billing_db',"SELECT count(*) FROM event_inbox WHERE related_id=1 AND status='ACCEPTED'")=='2'
        checks.append('new readers migrate previous databases; v1/v2 aliases and restart redelivery produce one notification and one business effect')
        customer=qa.Client(urls['customer-service']);gateway=qa.Client(urls['api-gateway']);private_password='FixturePrivacy1!';sessions=[]
        profile={'firstName':'Jan','lastName':'Fixture','street':'Testowa 1','postalCode':'00-001','city':'Test','country':'PL'}
        for email in ('owner@fixture.example','other@fixture.example','legacy@fixture.example','active@fixture.example'):
            qa.status(customer.get('/customers/register',method='POST',body={**profile,'email':email,'password':private_password}),201,'fixture registration')
            sql('customer_db',f"UPDATE customers SET enabled=true,verification_token_hash=NULL,verification_expires_at=NULL WHERE email='{email}'")
            sessions.append(qa.status(customer.get('/customers/login',method='POST',body={'email':email,'password':private_password}),200,'real login').document()['accessToken'])
        until(lambda:sql('notification_db',"SELECT count(*) FROM notifications WHERE event_type='CUSTOMER_REGISTERED' AND status='SENT'")=='4','registration SMTP delivery')
        for order,subject,status in ((1,1,'COMPLETED'),(4,1,'CANCELLED'),(2,2,'COMPLETED'),(3,3,'CANCELLED'),(5,4,'PAID')):
            completed=status=='COMPLETED'
            sql('order_db',f"INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,inventory_reservation_id,delivery_tracking_number,delivery_status,delivery_revision,invoice_requested,created_at,updated_at) VALUES({order},{subject},'owner@fixture.example',123.45,'{status}','Jan','Fixture','Testowa','00-001','Test','PL','STANDARD_COURIER',0,0,'HOME_DELIVERY','fixture-{order}',repeat('a',64),'customer:{subject}',{20+order if completed else 'NULL'},{repr('TRACK-'+str(order)) if completed else 'NULL'},{repr('DELIVERED') if completed else 'NULL'},0,{str(order==1).lower()},now(),now()); INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES({order},'{ 'COMPLETED' if completed else 'FAILED' }',now(),now())")
            if completed:
                sql('inventory_db',f"INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES({20+order},{order},'CONFIRMED',now(),now(),now())")
                sql('delivery_db',f"INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,sync_state,created_at,updated_at) VALUES({order},'TRACK-{order}','Carrier','COURIER','DELIVERED','SYNCED',now(),now())")
        for order,ref in ((1,'SIM-fixture'),(2,'pi_STRIPE_fixture')):
            sql('payment_db',f"INSERT INTO payments(idempotency_key,order_id,amount,currency,payment_method,status,provider_reference,request_fingerprint,result_recorded_at,failure_reason,created_at,updated_at) VALUES('fixture-payment-{order}',{order},123.45,'PLN','CARD','SUCCEEDED','{ref}',repeat('a',64),now(),'Obsolete diagnostic',now(),now())")
        sql('payment_db',"INSERT INTO payment_follow_ups(order_id,kind,payload,status,attempts,result_id,last_error_code,created_at,updated_at) VALUES(1,'PAYMENT_NOTIFICATION','{\"recipient\":\"owner@fixture.example\"}','SUCCEEDED',1,1,'OLD_DIAGNOSTIC',now(),now())")
        sql('billing_db',"INSERT INTO invoices(id,order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,currency,status,created_at) VALUES(1,1,1,'owner@fixture.example','FIX/1','PERSON','Jan Fixture','Testowa','00-001','Test','PL',100,23.45,123.45,'PLN','ISSUED',now())")
        # Both a published private outbox and its real Redis copy are reconciled explicitly.
        cache('XGROUP','CREATE','invoice-events','fixture-ledger','0','MKSTREAM')
        private_invoice={'eventType':'INVOICE_ISSUED','orderId':'1','invoiceId':'1','customerEmail':'owner@fixture.example','buyerName':'Jan Fixture'}
        sql('billing_db',"INSERT INTO invoice_outbox_events(event_id,order_id,payload,status,attempts,next_attempt_at,created_at) VALUES('invoice-issued-1',1,'"+json.dumps(private_invoice)+"','PENDING',0,now(),now())")
        until(lambda:sql('billing_db',"SELECT status FROM invoice_outbox_events WHERE event_id='invoice-issued-1'")=='PUBLISHED','actual invoice publisher')
        cache('XREADGROUP','GROUP','fixture-ledger','fixture-reader','COUNT','100','STREAMS','invoice-events','>')
        invoice_records=json.loads(cache('--json','XRANGE','invoice-events','-','+'))
        for record in invoice_records:cache('XACK','invoice-events','fixture-ledger',record[0])
        sql('returns_db',"INSERT INTO return_requests(order_id,customer_id,customer_email,request_type,reason,description,status,request_hash,order_verified_at,created_at,updated_at) VALUES(1,1,'owner@fixture.example','RETURN','Reason','Description','REJECTED',repeat('a',64),now(),now(),now())")
        # Explicit reset subject uses a different token ID and is safe to qualify.
        reset={'eventType':'PASSWORD_RESET_REQUESTED','sourceId':700,'customerId':1,'recipient':'owner@fixture.example','channel':'EMAIL','subject':'Reset','message':'Private reset text','simulateFailure':False}
        post('notification-service','/notifications',reset,expected=201)
        post('notification-service','/notifications',{**reset,'customerId':None},expected=400)
        post('notification-service','/notifications',reset,credentials['payment-service'],403)
        until(lambda:sql('notification_db',"SELECT status FROM notifications WHERE source_id=700 AND event_type='PASSWORD_RESET_REQUESTED'")=='SENT','verified reset notification')
        def confirm(identity):
            headers={'Authorization':'Bearer '+identity};preview=qa.status(customer.get('/customers/me/privacy-requests/preview',method='POST',headers={**headers,'Idempotency-Key':str(uuid.uuid4())}),200,'reviewed preview').document();token=secrets.token_urlsafe(32)
            qa.status(customer.get('/customers/me/privacy-requests/'+preview['requestId']+'/confirm',method='POST',headers=headers,body={'password':private_password,'statusToken':token,'planHash':preview['planHash'],'confirmation':'DELETE_MY_ACCOUNT'}),202,'explicit confirmation');return preview['requestId'],token
        requests=[confirm(identity) for identity in sessions]
        request_id,status_token=requests[0];legacy_id=requests[2][0]
        sql('customer_db',f"UPDATE account_privacy_requests SET finance_policy_hash=NULL WHERE id='{legacy_id}'")
        def state(index=0):
            identity,token=requests[index];return qa.status(customer.get('/customers/privacy-requests/'+identity+'/status',headers={'X-Privacy-Status-Token':token}),200,'safe status').document()
        until(lambda:all(row['fence_state']=='FENCED' for row in state()['services']),'fourteen actual fences')
        cmd={'requestId':request_id,'orderId':1,'orderVersion':0,'cursor':'0-0'}
        for name in ('payment-service','billing-service','notification-service','inventory-service','promotion-service','delivery-service','returns-service'):
            post(name,'/internal/privacy/finance-prepare/1',cmd,credentials['order-service'],409)
        assert sql('order_db','SELECT customer_email FROM orders WHERE id=1')=='owner@fixture.example'
        checks.append('default-off preparation; verified account subjects; reset token collision and chat notifications retained for separate review')
        print('PASS default-off and account ownership',flush=True)
        journal_fixture.acknowledge(urls['customer-service'],operator)
        enabled=True
        # Stop the coordinator before independently restarting its target adapters.
        stop('customer-service')
        for name in databases:
            if name!='customer-service':stop(name)
        adapters=[name for name in databases if name not in ('customer-service','order-service')]
        with ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(start,adapters))
        start('order-service');start('customer-service')
        # Exercise service-only ACLs and scope validation against real HTTP filters.
        for identity in (operator,credentials['customer-service']):
            post('payment-service','/internal/privacy/finance-prepare/1',cmd,identity,403)
        assert qa.Client(urls['payment-service']).get('/internal/privacy/finance-prepare/1',method='POST',headers={'Authorization':'Bearer '+sessions[1]},body=cmd).status in (401,403)
        post('payment-service','/internal/privacy/finance-prepare/1',{**cmd,'requestId':str(uuid.uuid4())},credentials['order-service'],409)
        post('payment-service','/internal/privacy/finance-prepare/1',{**cmd,'orderVersion':99},credentials['order-service'],409)
        # Restart a real peer while the durable job is waiting; generation retry is explicit.
        until(lambda:sql('order_db','SELECT count(*) FROM finance_privacy_jobs WHERE customer_id=1')=='2','independent discovery of both orders',150)
        stop('billing-service');start('billing-service')
        until(lambda:customer.get('/customers/privacy-requests/'+request_id+'/retry',method='POST',headers={'Authorization':'Bearer '+operator}).status==200,'explicit operator generation retry after peer outage')
        def complete():
            current=state()
            return current if current['financeCleanup']['state']=='DONE' and current['commerceCleanup']['completedScopes']==5 else None
        print('Running real seven-peer finance quorum',flush=True)
        current=until(complete,'all seven receipts release five commerce adapters',240)
        assert current['financeCleanup']['orders']==2 and current['financeCleanup']['preparedOrders']==2 and current['financeCleanup']['documentsRetained']
        assert not current['complete'] and not current['dataErased'] and not current['financeCleanup']['fullErasureComplete']
        assert sql('order_db','SELECT count(*) FROM finance_privacy_receipts WHERE order_id IN (1,4)')=='14'
        assert sql('order_db','SELECT count(*) FROM orders WHERE customer_id=1 AND privacy_redacted AND customer_email IS NULL AND delivery_street IS NULL')=='2'
        assert sql('payment_db','SELECT recipient IS NULL AND delivery_address IS NULL AND event_key LIKE \'retained:%\' AND stored_event_id IS NOT NULL FROM payment_outbox_events WHERE order_id=1')=='t'
        assert sql('payment_db','SELECT failure_reason IS NULL FROM payments WHERE order_id=1')=='t'
        assert sql('payment_db',"SELECT payload IS NULL AND last_error_code IS NULL AND result_id=1 AND status='SUCCEEDED' FROM payment_follow_ups WHERE order_id=1")=='t'
        assert sql('billing_db',"SELECT customer_email||'|'||buyer_name||'|'||gross_amount FROM invoices WHERE id=1")=='owner@fixture.example|Jan Fixture|123.45'
        assert sql('billing_db',"SELECT payload IS NULL FROM invoice_outbox_events WHERE event_id='invoice-issued-1'")=='t'
        assert sql('notification_db',"SELECT count(*) FROM notifications WHERE (source_id=1 AND event_type IN ('PAYMENT_SUCCEEDED','CUSTOMER_REGISTERED') OR subject_customer_id=1) AND (recipient IS NOT NULL OR message IS NOT NULL)")=='0'
        if legacy_available:assert sql('notification_db',"SELECT count(*) FROM notifications WHERE source_id=1 AND event_type IN ('PASSWORD_RESET_REQUESTED','CHAT_REPLY_SENT') AND message IS NOT NULL")=='2'
        assert sql('returns_db','SELECT privacy_redacted AND reason IS NULL AND description IS NULL FROM return_requests WHERE order_id=1')=='t'
        assert sql('inventory_db',"SELECT status FROM inventory_reservations WHERE order_id=1")=='CONFIRMED' and sql('delivery_db',"SELECT tracking_number FROM shipments WHERE order_id=1")=='TRACK-1'
        assert 'owner@fixture.example' not in json.dumps(current) and 'Testowa' not in json.dumps(current)
        checks.append('real SQL preparation, durable seven-peer quorum and independent two-order discovery release A43.35; contact and text erased, finance and business identifiers retained')
        until(lambda:sql('order_db',"SELECT code FROM finance_privacy_jobs WHERE order_id=2")=='WAITING_PROVIDER_REVIEW','Stripe remains manual review')
        assert sql('payment_db',"SELECT failure_reason FROM payments WHERE order_id=2")=='Obsolete diagnostic'
        assert sql('order_db','SELECT customer_email FROM orders WHERE id IN (2,3,5) ORDER BY id').splitlines()==['owner@fixture.example']*3
        assert not state(2)['financeCleanup']['policyReviewed'];assert state(3)['financeCleanup']['state']!='DONE'
        checks.append('Stripe footprints, active orders and historical unapproved policy wait without cancellation, external API calls or contact erasure')
        # Markers are always enforced, including after a process restart.
        stop('notification-service');start('notification-service');before=mails()
        late=event(1,kind='PAYMENT_FAILED');late2=event(1,kind='PAYMENT_PENDING',v2=True);bad={**event(1),'eventId':'malformed-late','amount':'not-money'}
        records=[add(fields) for fields in (late,late2,bad)]
        until(lambda:all(cache('XRANGE','payment-events',record,record)=='' for record in records),'late old/new/malformed copies ACKed and pruned',90)
        assert sql('notification_db',"SELECT count(*) FROM notifications WHERE source_id=1 AND event_type='PAYMENT_SUCCEEDED'")=='1' and mails()==before
        assert sql('notification_db',"SELECT count(*) FROM event_inbox WHERE order_id=1 AND status='SUPPRESSED' AND payload IS NULL AND error IS NULL")=='4'
        for db,query in [('order_db',"UPDATE orders SET customer_email='restored@fixture.example' WHERE id=1"),('payment_db',"UPDATE payment_outbox_events SET recipient='restored@fixture.example' WHERE order_id=1"),('notification_db',"UPDATE notifications SET recipient='restored@fixture.example' WHERE source_id=700")]:assert sql(db,query,True).returncode!=0
        checks.append('installed SQL and Redis fences survive restart; late v1/v2 and malformed payloads keep only suppressed receipts, no resend or PII restoration')
        correction={'reason':'Private correction reason','correctionGrossAmount':'-10.00','idempotencyKey':uuid.uuid4().hex,'expectedVersion':0}
        issued=post('billing-service','/invoices/1/corrections',correction,operator,201)
        until(lambda:sql('billing_db',f"SELECT status FROM invoice_outbox_events WHERE event_id='invoice-corrected-{issued['id']}'")=='PUBLISHED','financial correction still publishes')
        raw=sql('billing_db',f"SELECT privacy_safe||'|'||payload FROM invoice_outbox_events WHERE event_id='invoice-corrected-{issued['id']}'")
        assert raw.startswith('true|') and 'privacySuppressed' in raw and 'Private correction reason' not in raw and 'owner@fixture.example' not in raw
        assert sql('billing_db','SELECT correction_gross FROM invoice_corrections WHERE invoice_id=1')=='-10.00'
        assert sql('billing_db',"INSERT INTO invoice_outbox_events(event_id,order_id,payload,status,attempts,next_attempt_at,created_at) VALUES('unsafe-late',1,'{}','PENDING',0,now(),now())",True).returncode!=0
        for prefix in ('/api','/api/v1'):qa.status(gateway.get(prefix+'/customers/privacy-requests/'+request_id+'/status',headers={'X-Privacy-Status-Token':status_token}),200,'Gateway safe status')
        checks.append('retained invoices remain correctable; new events contain only approved financial metadata; old unsafe writer rejected; both Gateway prefixes return safe partial status')
        result={'stage':'A43.36','status':'PASS','checks':checks,'realApplications':list(names),'realDatabases':9,'realRedis':True,'realMailpit':True,'simulatedFencePeers':5,'actualPreviousReaders':2 if legacy_available else 0,'fullErasureComplete':False,'realUserDataAccessed':False,'externalProviderCalls':0,'deployment':False,'fixtureActivationOnly':True}
        (directory/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for name in reversed(list(processes)):stop(name)
        for held in reservations.values():held.close()
        for handle in handles:handle.close()
        for container in (mailpit,redis,postgres):
            if container:command('docker','rm','-f',container)
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join(timeout=5)

if __name__=='__main__':main()

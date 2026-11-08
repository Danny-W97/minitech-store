#!/usr/bin/env python3
"""A09: durable post-payment effects on two JVMs and real Billing/Delivery/Promotion.
Every process, port, token and PostgreSQL database belongs exclusively to this run.
"""
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('followup_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)


def main():
    logs=ROOT/'tmp'/('payment-follow-ups-'+secrets.token_hex(4));logs.mkdir(parents=True)
    processes={};handles=[];urls={};postgres=None;report={}
    password=secrets.token_urlsafe(24);operator=secrets.token_urlsafe(32);customer=secrets.token_urlsafe(32)
    tokens={name:secrets.token_urlsafe(32) for name in ['payment-service','billing-service','delivery-service','promotion-service']}
    lock=threading.Lock();order_states={};bad_billing_owner=set();status_calls={}
    buyer=dict(company=False,buyerName='Audit Buyer',street='PrivateAuditStreet',postalCode='00-001',city='City',country='PL')

    def order(source,billing=False):
        with lock:
            email=None if source==750 else 'owner@example.test'
            if billing and source in bad_billing_owner:email='changed@example.test'
            return dict(id=source,totalPrice=9,status=order_states.get(source,'CONFIRMED'),
                        items=[dict(id=1,productId=1,sku='SKU',productName='Product',quantity=1,unitPrice=10,totalPrice=10)],
                        inventoryReservationId=source,deliveryMethod='STANDARD_COURIER',deliveryCost=0,
                        promotionCode='WELCOME10',discountAmount=1,fulfillmentType='HOME_DELIVERY',
                        customerId=None,customerEmail=email,invoiceRequested=source!=750,lifecycleReviewRequired=False,couponReservationVerified=True)
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def reply(self,value,status=200):
            raw=json.dumps(value).encode();self.send_response(status);self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(raw)));self.end_headers()
            try:self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError):pass
        def body(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                parts=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size:self.rfile.readline();break
                    parts.append(self.rfile.read(size));self.rfile.read(2)
                raw=b''.join(parts)
            else:raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
            return json.loads(raw) if raw else {}
        def do_POST(self):
            data=self.body()
            if self.path=='/internal/auth/introspect':
                actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
                if data.get('token')==operator:actor['role']='OPERATOR'
                if data.get('token')==customer:actor.update(role='CUSTOMER',customerId=17,email='customer@example.test')
                for name,token in tokens.items():
                    if data.get('token')==token:actor.update(role='SERVICE',service=name)
                self.reply(actor)
            elif self.path.startswith('/inventory/reservations/') and self.path.endswith('/confirm'):self.reply({})
            else:self.reply({},404)
        def do_GET(self):
            if self.path.startswith('/internal/promotions/orders/') and self.path.endswith('/coupon'):
                source=int(self.path.split('/')[4]);snapshot=order(source)
                self.reply(dict(orderId=source,code=snapshot['promotionCode'],subtotal=snapshot['totalPrice']+snapshot['discountAmount']-snapshot['deliveryCost'],discountAmount=snapshot['discountAmount'],state='RESERVED'))
            elif self.path.startswith('/orders/'):
                is_billing=self.headers.get('Authorization')=='Bearer '+tokens['billing-service']
                self.reply(order(int(self.path.rsplit('/',1)[1]),is_billing))
            else:self.reply({},404)
        def do_PATCH(self):
            data=self.body();source=int(self.path.split('/')[2])
            with lock:
                order_states[source]=data['status'];status_calls[source]=status_calls.get(source,0)+1
            self.reply(order(source))
    fixture=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
    threading.Thread(target=fixture.serve_forever,daemon=True).start();fixture_url=f'http://127.0.0.1:{fixture.server_port}'

    def sql(database,query):
        return http.command('docker','exec',postgres,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d',database+'_db','-Atqc',query)
    def poll(action,predicate,seconds=30):
        deadline=time.monotonic()+seconds;value=None
        while time.monotonic()<deadline:
            value=action()
            if predicate(value):return value
            time.sleep(.1)
        raise AssertionError('Timed out; last result: '+repr(value))
    def module(name):return 'payment-service' if name.startswith('payment-') else name+'-service'
    def start(name):
        app=module(name);database=app.removesuffix('-service')
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        urls[name]=f'http://127.0.0.1:{port}'
        env={key:value for key,value in os.environ.items() if not key.startswith(('SPRING_','PLATFORM_','DB_','PAYMENT_','OUTBOX_','REDIS_','BILLING_','RESILIENCE_'))}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=str(port),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}_db?ApplicationName={name}',
                   DB_USERNAME='audit',DB_PASSWORD=password,PLATFORM_SERVICE_TOKEN=tokens[app],PLATFORM_AUTH_URL=fixture_url,
                   PLATFORM_ORDER_URL=fixture_url,ORDER_SERVICE_URL=fixture_url,INVENTORY_SERVICE_URL=fixture_url,
                   REDIS_URL='redis://127.0.0.1:1',MANAGEMENT_HEALTH_REDIS_ENABLED='false',BILLING_CONSUMER_ENABLED='false',
                   OUTBOX_PUBLISHER_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='2',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
                   PAYMENT_FOLLOW_UPS_FIXED_DELAY_MS='150',RESILIENCE_HTTP_CONNECT_TIMEOUT_MS='200',RESILIENCE_HTTP_READ_TIMEOUT_MS='300',
                   RESILIENCE_RETRY_MAX_ATTEMPTS='1',RESILIENCE_CB_MINIMUM_NUMBER_OF_CALLS='1000',RESILIENCE_CB_SLIDING_WINDOW_SIZE='1000')
        for dependency in ['billing','delivery','promotion']:
            if dependency in urls:env[dependency.upper()+'_SERVICE_URL']=urls[dependency]
        handle=(logs/(name+'-'+secrets.token_hex(2)+'.log')).open('w');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(jars/app)],env=env,stdout=handle,stderr=subprocess.STDOUT)
    def ready(name):
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError('Health timeout: '+name)
    def launch(names):
        for name in names:start(name)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(names)) as pool:list(pool.map(ready,names))
    def pay(name,source,invoice=True):
        # The fixture represents an already-accepted Order with its reserved quote.
        sql('promotion',f"INSERT INTO coupon_reservations(order_id,code,subtotal,discount_amount,state,accepted_at,expires_at,created_at,updated_at) VALUES ({source},'WELCOME10',10,1,'RESERVED',now(),now()+interval '40 minutes',now(),now()) ON CONFLICT (order_id) DO NOTHING")
        return http.request(urls[name]+'/payments','POST',dict(orderId=source,paymentMethod='CARD',simulateFailure=False,
                            invoiceRequested=invoice,billingData=buyer if invoice else None),token=operator,expected=201)
    def pending(source):return sql('payment',f"SELECT count(*) FROM payment_follow_ups WHERE order_id={source} AND status<>'SUCCEEDED'")
    def converged(source,invoice=True,notify=True):
        # A crashed JVM may also own other tasks for this order. Those retain the
        # real 45s lease even when the specifically faulted task is expired below.
        poll(lambda:pending(source),lambda value:value=='0',seconds=75)
        assert sql('delivery',f'SELECT count(*) FROM shipments WHERE order_id={source}')=='1'
        assert sql('promotion',f'SELECT count(*) FROM promotion_redemptions WHERE order_id={source}')=='1'
        if invoice:assert sql('billing',f'SELECT count(*) FROM invoices WHERE order_id={source}')=='1'
        if notify:
            assert sql('payment',f"SELECT count(*) FROM payment_outbox_events WHERE order_id={source} AND event_type='PAYMENT_SUCCEEDED'")=='1'
            if invoice:
                assert sql('payment',f'SELECT invoice_id FROM payment_outbox_events WHERE order_id={source}')==sql('billing',f'SELECT id FROM invoices WHERE order_id={source}')
        assert sql('payment',f'SELECT count(*) FROM payment_follow_ups WHERE order_id={source} AND payload IS NOT NULL')=='0'
    def trigger(source,kind):
        sql('payment',f"CREATE FUNCTION pause_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.order_id={source} AND NEW.kind='{kind}' AND NEW.status='SUCCEEDED' THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_completion BEFORE UPDATE ON payment_follow_ups FOR EACH ROW EXECUTE FUNCTION pause_completion()")
    def kill(name):
        processes[name].kill();processes[name].wait(10)
        # Only this disposable DB and this killed JVM's sessions are affected.
        sql('payment',f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{name}' AND datname='payment_db'")
    try:
        jars=logs/'jars';jars.mkdir()
        for app in tokens:shutil.copyfile(next((ROOT/app/'target').glob('*-SNAPSHOT.jar')),jars/app)
        postgres=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Temporary PostgreSQL not ready')
        for database in ['payment','billing','delivery','promotion']:http.command('docker','exec',postgres,'createdb','-U','audit',database+'_db')
        launch(['billing','delivery','promotion']);launch(['payment-a','payment-b'])
        print('READY: two Payment replicas and real Billing/Delivery/Promotion with disposable PostgreSQL',flush=True)

        for name in ['billing','delivery','promotion']:processes[name].send_signal(signal.SIGSTOP)
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
                responses=list(pool.map(lambda source:pay('payment-a' if source%2 else 'payment-b',source),range(700,705)))
            assert all(row['status']=='SUCCEEDED' for row in responses)
            assert sql('payment',"SELECT count(*) FROM payment_follow_ups WHERE order_id BETWEEN 700 AND 704")=='20'
            assert sql('payment',"SELECT count(*) FROM payment_outbox_events WHERE order_id BETWEEN 700 AND 704")=='0'
            assert sql('payment',"SELECT count(*) FROM payment_follow_ups WHERE order_id BETWEEN 700 AND 704 AND kind='PAYMENT_NOTIFICATION' AND status='WAITING'")=='5'
            print('PASS: successful payments and 20 durable tasks while all three dependencies are unavailable; invoice mail waits',flush=True)
        finally:
            for name in ['billing','delivery','promotion']:processes[name].send_signal(signal.SIGCONT)
        for source in range(700,705):converged(source)
        assert sql('promotion',"SELECT redeemed_count FROM promotion_codes WHERE code='WELCOME10'")=='5'
        assert all(status_calls[source]==1 for source in range(700,705))
        report['offlineOrdersRecovered']=5
        print('PASS: automatic invoice/shipment/coupon recovery without another payment POST, exact shared coupon count and PDF event IDs',flush=True)

        pay('payment-a',750,invoice=False);converged(750,invoice=False,notify=False)
        assert sql('payment',"SELECT count(*) FROM payment_follow_ups WHERE order_id=750")=='2'
        assert sql('payment',"SELECT count(*) FROM payment_outbox_events WHERE order_id=750")=='0'
        print('PASS: coupon/shipment jobs exist and finish without any notification recipient',flush=True)

        for index,kind in enumerate(['PROMOTION_REDEMPTION','SHIPMENT_TRACKING','INVOICE_ISSUE','PAYMENT_NOTIFICATION']):
            source=800+index;trigger(source,kind)
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                future=pool.submit(pay,'payment-a',source)
                owner=poll(lambda:sql('payment',"SELECT application_name FROM pg_stat_activity WHERE wait_event='PgSleep' AND application_name LIKE 'payment-%' LIMIT 1"),bool)
                assert sql('payment',f"SELECT status FROM payments WHERE order_id={source}")=='SUCCEEDED'
                kill(owner)
                sql('payment','DROP TRIGGER pause_completion ON payment_follow_ups; DROP FUNCTION pause_completion()')
                sql('payment',f"UPDATE payment_follow_ups SET processing_until=now()-interval '1 second' WHERE order_id={source} AND kind='{kind}'")
                try:future.result(15)
                except (OSError,urllib.error.URLError):pass
            converged(source)
            start(owner);ready(owner)
            print('PASS: owner crash after '+kind+' effect/before local completion; no duplicate business result',flush=True)
        report['effectCompletionCrashPoints']=4

        bad_billing_owner.add(900);pay('payment-b',900)
        poll(lambda:sql('payment',"SELECT status FROM payment_follow_ups WHERE order_id=900 AND kind='INVOICE_ISSUE'"),lambda value:value=='DEAD_LETTER')
        assert sql('payment',"SELECT status FROM payment_follow_ups WHERE order_id=900 AND kind='PAYMENT_NOTIFICATION'")=='WAITING'
        assert sql('billing','SELECT count(*) FROM invoices WHERE order_id=900')=='0'
        tasks=http.request(urls['payment-a']+'/payments/follow-ups?orderId=900',token=operator)
        assert all(not {'payload','recipient','billingData','address'}.intersection(row) for row in tasks)
        assert 'PrivateAuditStreet' not in json.dumps(tasks)
        failed=next(row for row in tasks if row['kind']=='INVOICE_ISSUE');assert failed['lastErrorCode']=='HTTP_400'
        for token,expected in [(None,401),(customer,403),(tokens['payment-service'],403)]:
            http.request(urls['payment-a']+'/payments/follow-ups',token=token,expected=expected)
            http.request(urls['payment-a']+f'/payments/follow-ups/{failed["id"]}/replay','POST',token=token,expected=expected)
        bad_billing_owner.remove(900)
        http.request(urls['payment-a']+f'/payments/follow-ups/{failed["id"]}/replay','POST',token=operator)
        converged(900)
        http.request(urls['payment-b']+f'/payments/follow-ups/{failed["id"]}/replay','POST',token=operator,expected=409)
        http.request(urls['payment-a']+'/payments/follow-ups?size=201',token=operator,expected=400)
        print('PASS: permanent mismatch is visible in operator DLQ; authorized replay repairs invoice/mail; success and private data are protected',flush=True)

        assert sql('payment',"SELECT count(*) FROM pg_stat_activity WHERE datname='payment_db' AND state='idle in transaction'")=='0'
        total=sql('promotion','SELECT count(*) FROM promotion_redemptions')
        assert sql('promotion',"SELECT redeemed_count FROM promotion_codes WHERE code='WELCOME10'")==total
        report.update(result='PASSED',paymentReplicas=2,realDependencies=['billing','delivery','promotion'],redeemedOrders=int(total))
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('PASS A09: durable independent effects, crash recovery, idempotent targets, invoice notification gating and operator replay',flush=True)
    except Exception:
        report['result']='FAILED'
        if postgres:
            try:
                report['unfinishedTasks']=json.loads(sql('payment',"SELECT coalesce(json_agg(t),'[]') FROM (SELECT id,order_id,kind,status,attempts,last_error_code,next_attempt_at,processing_until FROM payment_follow_ups WHERE status<>'SUCCEEDED' ORDER BY id) t"))
            except Exception:report['unfinishedTasks']='unavailable'
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        raise
    finally:
        for process in processes.values():
            if process.poll() is None:process.send_signal(signal.SIGCONT);process.terminate()
        for process in processes.values():
            try:process.wait(timeout=12)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for handle in handles:handle.close()
        if postgres:subprocess.run(['docker','rm','-f',postgres],capture_output=True)
        fixture.shutdown();fixture.server_close()
        print('Isolated resources removed; logs:',logs,flush=True)


if __name__=='__main__':main()

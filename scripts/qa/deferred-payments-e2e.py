#!/usr/bin/env python3
"""A10: real Order/Inventory/Payment/Delivery/Billing/Promotion/Review, disposable PostgreSQL.
Two production-profile Payment JVMs have no online provider. Only owned processes
and databases are paused, killed or migrated; all credentials and ports are random.
"""
import concurrent.futures
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
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
import urllib.parse
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('cash_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)


def main(cancellation=False,lifecycle=False,coupons=False,idempotency=False,returns=False):
    logs=ROOT/'tmp'/(('returns-workflow-' if returns else 'order-idempotency-' if idempotency else 'coupon-reservations-' if coupons else 'order-lifecycle-' if lifecycle else 'cancellations-' if cancellation else 'deferred-payments-')+secrets.token_hex(4));logs.mkdir(parents=True)
    modules=['order','inventory','delivery','returns','payment'] if returns else ['order','inventory','billing','delivery','promotion','review','payment']
    nodes=[name for name in modules if name!='payment']+['payment-a','payment-b'];processes={};handles=[];urls={};ports={};postgres=None;report={}
    if lifecycle or coupons or idempotency:nodes.append('order-b')
    if coupons:nodes.append('promotion-b')
    if returns:nodes.append('returns-b')
    coupon_control={'quote_barrier':None,'reserve_counter':0,'drop_reserve':None,'drop_release':None,'fail_lookup':False}
    coupon_lock=threading.Lock()
    intent_control={'product_barrier':None,'product_unavailable':False,'drop_inventory':False,'other_customer':None,'product_requests':0}
    return_control={'validation_barrier':None,'unavailable':False,'migration_stage':True,'other_customer':None}
    password=secrets.token_urlsafe(24);operator=secrets.token_urlsafe(32);customer=secrets.token_urlsafe(32)
    tokens={name+'-service':secrets.token_urlsafe(32) for name in modules}
    buyer=dict(company=False,buyerName='Audit Buyer',street='Audit Street',postalCode='00-001',city='City',country='PL')
    for name in nodes:
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));ports[name]=probe.getsockname()[1]
        urls[name]='http://127.0.0.1:'+str(ports[name])
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def reply(self,value,status=200):
            raw=json.dumps(value).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers()
            try:self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError):pass
        def do_GET(self):
            parsed=urllib.parse.urlsplit(self.path)
            if returns and parsed.path.startswith('/orders/'):
                if return_control['unavailable']:self.reply({},503);return
                request=urllib.request.Request(urls['order']+self.path,headers={'Authorization':self.headers.get('Authorization','')})
                try:
                    with urllib.request.urlopen(request,timeout=20) as response:value=json.loads(response.read())
                except urllib.error.HTTPError as error:self.reply(json.loads(error.read()),error.code);return
                barrier=return_control['validation_barrier']
                if barrier is not None and barrier.wait(20)==0:return_control['validation_barrier']=None
                self.reply(value);return
            if coupons and parsed.path.startswith('/internal/promotions/'):
                if coupon_control['fail_lookup']:self.reply({},503);return
                self.coupon_proxy(None);return
            if parsed.path=='/internal/products/batch':
                if idempotency:
                    intent_control['product_requests']+=1
                    if intent_control['product_unavailable']:self.reply({},503);return
                    barrier=intent_control['product_barrier']
                    if barrier is not None and barrier.wait(20)==0:intent_control['product_barrier']=None
                ids=urllib.parse.parse_qs(parsed.query)['ids'][0].split(',')
                self.reply([dict(id=int(item),sku='SKU-'+item,name='Audit product',category='Test',manufacturer='Test',model='Test',
                                 technicalParameters={},price=10,regularPrice=10,discountPercent=0,promotional=False,newProduct=False,
                                 rotationDate='2026-10-25',active=True,createdAt='2026-10-25T00:00:00Z',updatedAt='2026-10-25T00:00:00Z') for item in ids])
            else:self.reply({},404)
        def do_POST(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                parts=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size:self.rfile.readline();break
                    parts.append(self.rfile.read(size));self.rfile.read(2)
                data=json.loads(b''.join(parts))
            else:data=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            if idempotency and self.path=='/inventory/reservations':
                request=urllib.request.Request(urls['inventory']+self.path,data=json.dumps(data).encode(),headers={'Authorization':self.headers.get('Authorization',''),'Content-Type':'application/json'},method='POST')
                try:
                    with urllib.request.urlopen(request,timeout=20) as response:result=json.loads(response.read())
                except urllib.error.HTTPError as error:self.reply(json.loads(error.read()),error.code);return
                if intent_control['drop_inventory']:
                    intent_control['drop_inventory']=False;self.connection.close();return
                self.reply(result);return
            if coupons and (self.path.startswith('/promotions/') or self.path.startswith('/internal/promotions/')):
                self.coupon_proxy(data);return
            actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
            if data.get('token')==operator:actor['role']='OPERATOR'
            if data.get('token')==customer:actor.update(role='CUSTOMER',customerId=17,email='owner@example.test')
            if idempotency and data.get('token')==intent_control['other_customer']:actor.update(role='CUSTOMER',customerId=18,email='other@example.test')
            if returns and data.get('token')==return_control['other_customer']:actor.update(role='CUSTOMER',customerId=18,email='other@example.test')
            for app,token in tokens.items():
                if data.get('token')==token:actor.update(role='SERVICE',service=app)
            self.reply(actor)
        def coupon_proxy(self,data):
            target='promotion'
            if self.path.endswith('/reserve'):
                with coupon_lock:
                    target='promotion-b' if coupon_control['reserve_counter']%2 else 'promotion'
                    coupon_control['reserve_counter']+=1
            request=urllib.request.Request(urls[target]+self.path,data=None if data is None else json.dumps(data).encode(),
                headers={'Authorization':self.headers.get('Authorization',''),'Content-Type':'application/json'},method='GET' if data is None else 'POST')
            try:
                with urllib.request.urlopen(request,timeout=20) as response:result=json.loads(response.read())
            except urllib.error.HTTPError as error:
                try:result=json.loads(error.read())
                except ValueError:result={}
                self.reply(result,error.code);return
            except OSError:self.reply({},503);return
            if self.path=='/promotions/codes/validate' and data.get('code')=='LAST-ONE':
                barrier=coupon_control['quote_barrier']
                if barrier is not None and result.get('valid'):
                    if barrier.wait(20)==0:coupon_control['quote_barrier']=None
            for suffix,key in [('/reserve','drop_reserve'),('/release','drop_release')]:
                if self.path.endswith(suffix) and data.get('code')==coupon_control[key]:
                    coupon_control[key]=None;self.connection.close();return
            self.reply(result)
    fixture=ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(database,query):return http.command('docker','exec',postgres,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d',database+'_db','-Atqc',query)
    def poll(action,predicate,seconds=75):
        deadline=time.monotonic()+seconds;value=None
        while time.monotonic()<deadline:
            value=action()
            if predicate(value):return value
            time.sleep(.1)
        raise AssertionError('Timed out; last result: '+repr(value))
    def app(name):return name.rsplit('-',1)[0] if name.endswith(('-a','-b')) else name
    def start(name):
        domain=app(name);module=domain+'-service'
        env={key:value for key,value in os.environ.items() if not key.startswith(('SPRING_','PLATFORM_','DB_','PAYMENT_','OUTBOX_','REDIS_','BILLING_','RESILIENCE_','INVENTORY_','ORDER_'))}
        env.update(SPRING_PROFILES_ACTIVE='prod' if domain=='payment' else 'dev',SERVER_PORT=str(ports[name]),
                   DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{domain}_db?ApplicationName={name}',DB_USERNAME='audit',DB_PASSWORD=password,
                   PLATFORM_SERVICE_TOKEN=tokens[module],PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=urls['order'],
                   PRODUCT_SERVICE_URL=fixture_url,PAYMENT_PROVIDER='disabled',REDIS_URL='redis://127.0.0.1:1',
                   MANAGEMENT_HEALTH_REDIS_ENABLED='false',OUTBOX_PUBLISHER_ENABLED='false',BILLING_CONSUMER_ENABLED='false',
                   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
                   ORDER_SAGA_RECOVERY_FIXED_DELAY_MS='600000',INVENTORY_RESERVATION_TTL_SECONDS='10',INVENTORY_RESERVATION_CLEANUP_FIXED_DELAY_MS='200',
                   PAYMENT_FOLLOW_UPS_FIXED_DELAY_MS='150',RESILIENCE_HTTP_CONNECT_TIMEOUT_MS='1000',RESILIENCE_HTTP_READ_TIMEOUT_MS='3000',
                   RESILIENCE_RETRY_MAX_ATTEMPTS='1',RESILIENCE_CB_MINIMUM_NUMBER_OF_CALLS='1000',RESILIENCE_CB_SLIDING_WINDOW_SIZE='1000')
        for other in modules:
            if other!='payment':env[other.upper()+'_SERVICE_URL']=urls[other]
        if coupons and domain in ('order','payment'):env['PROMOTION_SERVICE_URL']=fixture_url
        if idempotency and domain=='order':env['INVENTORY_SERVICE_URL']=fixture_url
        if returns and domain=='returns':
            env['ORDER_SERVICE_URL']=fixture_url
            if return_control['migration_stage']:env.update(SPRING_FLYWAY_TARGET='2',SPRING_JPA_HIBERNATE_DDL_AUTO='none')
        if coupons:env.update(COUPON_EXPIRY_DELAY_MS='200',ORDER_SAGA_RECOVERY_FIXED_DELAY_MS='150')
        env['PAYMENT_SERVICE_URL']=urls['payment-a']
        if cancellation or lifecycle or coupons or idempotency or returns:
            env.update(ORDER_SAGA_RECOVERY_FIXED_DELAY_MS='600000' if idempotency else '150',INVENTORY_RESERVATION_TTL_SECONDS='600')
        if lifecycle:env.update(ORDER_SAGA_RECONCILIATION_FIXED_DELAY_MS='200',ORDER_SAGA_RECONCILIATION_BATCH_SIZE='2')
        handle=(logs/(name+'-'+secrets.token_hex(2)+'.log')).open('w');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(jars/module)],env=env,stdout=handle,stderr=subprocess.STDOUT)
    def ready(name):
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError('Health timeout: '+name)
    def call(name,path,method='GET',body=None,token=operator,expected=200):
        return http.request(urls[name]+path,method,body,token=token,expected=expected)
    def create(pickup=False):
        order=call('order','/orders','POST',dict(items=[dict(productId=1,quantity=1)],customerId=17,customerEmail='owner@example.test',
                deliveryAddress=dict(firstName='Jan',lastName='Audit',street='Street 1',postalCode='00-001',city='City',country='PL'),
                deliveryMethod='STORE_PICKUP' if pickup else 'STANDARD_COURIER',invoiceRequested=True,promotionCode='WELCOME10'),token=customer,expected=201)
        return poll(lambda:call('order','/orders/'+str(order['id'])),lambda row:row['status']=='CONFIRMED')
    def accept(order,pickup=False,name='payment-a',expected=201):
        return call(name,'/payments','POST',dict(orderId=order['id'],paymentMethod='CASH_AT_PICKUP' if pickup else 'CASH_ON_DELIVERY',
                    simulateFailure=False,customerId=17,invoiceRequested=True,billingData=buyer),token=customer,expected=expected)
    def fulfillment(order):
        source=order['id']
        poll(lambda:sql('payment',f"SELECT count(*) FROM payment_follow_ups WHERE order_id={source} AND kind IN ('PROMOTION_REDEMPTION','SHIPMENT_TRACKING','PAYMENT_PENDING_NOTIFICATION') AND status='SUCCEEDED'"),lambda count:count=='3')
        assert sql('inventory',f"SELECT status FROM inventory_reservations WHERE order_id={source}")=='CONFIRMED'
        assert sql('billing',f'SELECT count(*) FROM invoices WHERE order_id={source}')=='0'
        assert call('order','/orders/'+str(source))['paymentState']=='COLLECTION_DUE'
    def receipt(order,reference):return dict(amount=order['totalPrice'],currency='PLN',receiptReference=reference)
    def collect(payment,order,reference,name='payment-a',token=operator,expected=200):
        return call(name,f'/payments/{payment["id"]}/collect','POST',receipt(order,reference),token=token,expected=expected)
    def handed(order,pickup=False):
        return call('delivery',f'/deliveries/orders/{order["id"]}/tracking','PATCH',dict(status='PICKED_UP' if pickup else 'DELIVERED'))
    def settled(order):
        source=order['id']
        poll(lambda:sql('payment',f"SELECT count(*) FROM payment_follow_ups WHERE order_id={source} AND status<>'SUCCEEDED'"),lambda count:count=='0')
        actual=call('order','/orders/'+str(source));assert actual['paymentState']=='PAID' and actual['status']=='COMPLETED',actual
        assert sql('billing',f'SELECT count(*) FROM invoices WHERE order_id={source}')=='1'
        assert sql('delivery',f'SELECT count(*) FROM shipments WHERE order_id={source}')=='1'
        assert sql('promotion',f'SELECT count(*) FROM promotion_redemptions WHERE order_id={source}')=='1'
        assert sql('payment',f"SELECT count(*) FROM payment_outbox_events WHERE order_id={source} AND event_type='PAYMENT_SUCCEEDED'")=='1'
        assert sql('payment',f"SELECT invoice_id FROM payment_outbox_events WHERE order_id={source} AND event_type='PAYMENT_SUCCEEDED'")==sql('billing',f'SELECT id FROM invoices WHERE order_id={source}')
    try:
        jars=logs/'jars';jars.mkdir()
        for module in tokens:shutil.copyfile(next((ROOT/module/'target').glob('*-SNAPSHOT.jar')),jars/module)
        postgres=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PostgreSQL not ready')
        for domain in modules:http.command('docker','exec',postgres,'createdb','-U','audit',domain+'_db')
        initial=[name for name in nodes if not returns or not name.startswith('returns')]
        for name in initial:start(name)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(initial)) as pool:list(pool.map(ready,initial))
        if returns:
            start('returns');ready('returns')
            sql('returns',"INSERT INTO return_requests(id,order_id,customer_id,customer_email,request_type,reason,description,status,moderation_note,created_at,updated_at) VALUES (9001,900001,17,'owner@example.test','RETURN','Old reason','Old description','REQUESTED','Original note',now(),now()),(9002,900002,17,'owner@example.test','COMPLAINT','Old complaint','Old complaint description','COMPLETED','Original completion',now(),now())")
            processes['returns'].kill();processes['returns'].wait(10)
            return_control['migration_stage']=False
            start('returns');ready('returns');start('returns-b');ready('returns-b')
        sql('inventory','UPDATE inventory_items SET available_quantity=1000,reserved_quantity=0,sold_quantity=0 WHERE product_id=1')
        call('delivery','/deliveries/quote','POST',dict(methodCode='STANDARD_COURIER',postalCode='00-001',itemCount=1,subtotal=10),token=tokens['order-service'])
        print('READY: real fulfillment services, two production Payment replicas without an online provider',flush=True)

        if returns:
            scenario=importlib.util.spec_from_file_location('returns_checks',ROOT/'scripts/qa/returns-workflow-e2e.py')
            checks=importlib.util.module_from_spec(scenario);scenario.loader.exec_module(checks)
            checks.run(logs,call,sql,poll,tokens,customer,operator,urls,http,return_control,processes,start,ready)
            return

        if idempotency:
            scenario=importlib.util.spec_from_file_location('intent_checks',ROOT/'scripts/qa/order-idempotency-e2e.py')
            checks=importlib.util.module_from_spec(scenario);scenario.loader.exec_module(checks)
            checks.run(logs,call,sql,poll,tokens,customer,operator,urls,http,intent_control,processes)
            return

        if coupons:
            scenario=importlib.util.spec_from_file_location('coupon_checks',ROOT/'scripts/qa/coupon-reservations-e2e.py')
            checks=importlib.util.module_from_spec(scenario);scenario.loader.exec_module(checks)
            checks.run(logs,call,sql,poll,tokens,customer,operator,urls,http,coupon_control)
            return

        if lifecycle:
            scenario=importlib.util.spec_from_file_location('lifecycle_checks',ROOT/'scripts/qa/order-lifecycle-e2e.py')
            checks=importlib.util.module_from_spec(scenario);scenario.loader.exec_module(checks)
            checks.run(logs,call,create,accept,sql,processes,start,ready,poll,tokens,customer,operator,urls,http)
            return

        if cancellation:
            scenario=importlib.util.spec_from_file_location('cancellation_checks',ROOT/'scripts/qa/cancellations-e2e.py')
            checks=importlib.util.module_from_spec(scenario);scenario.loader.exec_module(checks)
            checks.run(logs,call,create,accept,sql,processes,start,ready,poll,tokens,customer,operator)
            return

        courier=create();pending=accept(courier);assert pending['status']=='PENDING' and pending['providerReference'] is None
        fulfillment(courier)
        time.sleep(11)
        assert sql('inventory',f"SELECT status FROM inventory_reservations WHERE order_id={courier['id']}")=='CONFIRMED'
        assert sql('inventory','SELECT sold_quantity FROM inventory_items WHERE product_id=1')=='1'
        call('order',f'/orders/{courier["id"]}/status','PATCH',dict(status='SHIPPED'))
        assert call('order',f'/orders/{courier["id"]}')['paymentState']=='COLLECTION_DUE'
        for token,expected in [(None,401),(customer,403),(tokens['delivery-service'],403)]:collect(pending,courier,'COD-RECEIPT-1',token=token,expected=expected)
        collect(pending,courier,'COD-RECEIPT-1',expected=409)
        call('order',f'/orders/{courier["id"]}/status','PATCH',dict(status='PAID'),expected=409)
        call('order',f'/orders/{courier["id"]}/status','PATCH',dict(status='CANCELLED'),expected=409)
        call('review','/reviews','POST',dict(customerId=17,orderId=courier['id'],productId=1,rating=5,title='Review',content='Content'),token=customer,expected=400)
        print('PASS: courier cash order retains confirmed stock beyond TTL, ships unpaid, has tracking and rejects unauthorized/premature collection',flush=True)
        handed(courier)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            first=pool.submit(collect,pending,courier,'COD-RECEIPT-1','payment-a')
            second=pool.submit(collect,pending,courier,'COD-RECEIPT-1','payment-b')
            recorded=first.result(20);assert second.result(20)==recorded
        settled(courier);assert collect(pending,courier,'COD-RECEIPT-1')==recorded
        collect(pending,courier,'OTHER-RECEIPT',expected=409)
        wrong=receipt(courier,'COD-RECEIPT-1');wrong['amount']=.01
        call('payment-b',f'/payments/{pending["id"]}/collect','POST',wrong,expected=409)
        call('review','/reviews','POST',dict(customerId=17,orderId=courier['id'],productId=1,rating=5,title='Review',content='Collected purchase'),token=customer,expected=201)
        print('PASS: two replicas record one identical receipt and one invoice; completed collected orders qualify as paid purchases',flush=True)

        pickup=create(True);pickup_payment=accept(pickup,True);fulfillment(pickup)
        handed(pickup,True);collect(pickup_payment,pickup,'COD-RECEIPT-1',expected=409)
        assert call('payment-a',f'/payments/{pickup_payment["id"]}',token=customer)['status']=='PENDING'
        collect(pickup_payment,pickup,'PICKUP-RECEIPT-1');settled(pickup)
        print('PASS: pickup follows its own handover status and cannot reuse another order receipt',flush=True)

        recovery=create();recovery_payment=accept(recovery);fulfillment(recovery);handed(recovery)
        source=recovery['id']
        sql('payment',f"CREATE FUNCTION pause_settlement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.order_id={source} AND NEW.kind='ORDER_SETTLEMENT' AND NEW.status='SUCCEEDED' THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_settlement BEFORE UPDATE ON payment_follow_ups FOR EACH ROW EXECUTE FUNCTION pause_settlement()")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(collect,recovery_payment,recovery,'RECOVERY-RECEIPT')
            owner=poll(lambda:sql('payment',"SELECT application_name FROM pg_stat_activity WHERE wait_event='PgSleep' AND application_name LIKE 'payment-%' LIMIT 1"),bool)
            assert call('order',f'/orders/{source}')['paymentState']=='PAID'
            processes[owner].kill();processes[owner].wait(10)
            sql('payment',f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{owner}' AND datname='payment_db'")
            sql('payment','DROP TRIGGER pause_settlement ON payment_follow_ups; DROP FUNCTION pause_settlement()')
            sql('payment',f"UPDATE payment_follow_ups SET processing_until=now()-interval '1 second' WHERE order_id={source} AND processing_token IS NOT NULL")
            try:future.result(15)
            except (OSError,urllib.error.URLError):pass
        settled(recovery);start(owner);ready(owner)
        print('PASS: crash after Order settlement/before task commit recovers without another collection POST or duplicate effects',flush=True)

        failed=create();processes['inventory'].send_signal(signal.SIGSTOP)
        try:
            accept(failed,expected=503)
            assert call('order',f'/orders/{failed["id"]}')['paymentState']=='UNPAID'
            assert sql('payment',f"SELECT count(*) FROM payment_follow_ups WHERE order_id={failed['id']}")=='0'
        finally:processes['inventory'].send_signal(signal.SIGCONT)
        failure_payment=accept(failed);fulfillment(failed);handed(failed);collect(failure_payment,failed,'RETRY-RECEIPT');settled(failed)
        print('PASS: failed stock confirmation cannot report acceptance; an explicit retry confirms stock and completes normally',flush=True)
        online=create()
        call('payment-a','/payments','POST',dict(orderId=online['id'],paymentMethod='CARD',simulateFailure=False),token=customer,expected=503)
        for path in [f'/internal/orders/{online["id"]}/deferred/accept',f'/internal/orders/{online["id"]}/deferred/collect']:
            call('order',path,'POST',{},token=operator,expected=403)
        assert sql('payment',"SELECT count(*) FROM pg_stat_activity WHERE datname='payment_db' AND state='idle in transaction'")=='0'
        report.update(result='PASSED',paymentReplicas=2,confirmedBeyondTtl=True,cashOrdersSettled=4,onlineProvider='disabled',httpChecks=http.CHECKS)
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('PASS A10: cash fulfillment, TTL safety, authorized idempotent collection, asynchronous settlement and invoice gating',flush=True)
    except Exception:
        report['result']='FAILED'
        if postgres:
            try:report['unfinishedTasks']=json.loads(sql('payment',"SELECT coalesce(json_agg(t),'[]') FROM (SELECT order_id,kind,status,attempts,last_error_code,processing_until FROM payment_follow_ups WHERE status<>'SUCCEEDED') t"))
            except Exception:report['unfinishedTasks']='unavailable'
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8');raise
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

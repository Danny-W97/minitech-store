#!/usr/bin/env python3
"""A17: real Order/Review, two Delivery JVMs, disposable PostgreSQL and an owned fault proxy."""
import concurrent.futures
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('lifecycle_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)

def main():
    logs=ROOT/'tmp'/('purchased-lifecycle-'+secrets.token_hex(4));logs.mkdir(parents=True)
    processes={};handles=[];urls={};container=None;report={'result':'FAILED'}
    password,operator,customer=(secrets.token_urlsafe(32) for _ in range(3))
    tokens={name+'-service':secrets.token_urlsafe(32) for name in ['order','review','delivery','recommendation','payment']}
    drop_once=set();committed=threading.Event();release_reply=threading.Event();pause_order=None
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def reply(self,value,code=200):
            raw=value if isinstance(value,bytes) else json.dumps(value).encode()
            self.send_response(code);self.send_header('Content-Type','application/json');self.end_headers()
            try:self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError):pass
        def body(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                chunks=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size:self.rfile.readline();break
                    chunks.append(self.rfile.read(size));self.rfile.read(2)
                return b''.join(chunks)
            return self.rfile.read(int(self.headers.get('Content-Length','0')))
        def do_POST(self):
            raw=self.body()
            if self.path=='/internal/auth/introspect':
                token=json.loads(raw).get('token');actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
                if token==operator:actor['role']='OPERATOR'
                elif token==customer:actor.update(role='CUSTOMER',customerId=17,email='owner@example.test')
                else:
                    for name,value in tokens.items():
                        if token==value:actor.update(role='SERVICE',service=name);break
                self.reply(actor);return
            if self.path.startswith('/internal/orders/') and self.path.endswith('/shipment-progress'):
                assert self.headers.get('Authorization')=='Bearer '+tokens['delivery-service'],'Unexpected identity on shipment progress'
                order_id=int(self.path.split('/')[3])
                request=urllib.request.Request(urls['order']+self.path,data=raw,headers={'Authorization':self.headers['Authorization'],'Content-Type':'application/json'},method='POST')
                try:
                    with urllib.request.urlopen(request,timeout=10) as response:result=response.read()
                except urllib.error.HTTPError as error:self.reply(error.read(),error.code);return
                except OSError:self.reply({},503);return
                if order_id in drop_once:
                    drop_once.remove(order_id);self.connection.close();return
                if order_id==pause_order:
                    committed.set();release_reply.wait(20)
                self.reply(result);return
            self.reply({},404)
    class FixtureServer(ThreadingHTTPServer):
        daemon_threads=True
        request_queue_size=64
    fixture=FixtureServer(('127.0.0.1',0),Fixture)
    threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(domain,query):return http.command('docker','exec',container,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d',domain+'_db','-Atqc',query)
    def start(name,legacy=False,recovery=False):
        domain='delivery' if name.startswith('delivery') else name
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        urls[name]=f'http://127.0.0.1:{port}'
        env={key:value for key,value in os.environ.items() if not key.startswith(('SPRING_','PLATFORM_','DB_','ORDER_','DELIVERY_','REVIEW_','RESILIENCE_','REDIS_'))}
        env.update(SPRING_PROFILES_ACTIVE='test',SERVER_PORT=str(port),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{domain}_db',DB_USERNAME='audit',DB_PASSWORD=password,
            PLATFORM_SERVICE_TOKEN=tokens[domain+'-service'],PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=urls['order'],
            ORDER_SERVICE_URL=fixture_url if domain=='delivery' else urls['order'],ORDER_SAGA_RECOVERY_ENABLED='false',
            DELIVERY_SYNC_ENABLED='true' if recovery else 'false',DELIVERY_SYNC_DELAY_MS='200',
            SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='5',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
            RESILIENCE_RETRY_MAX_ATTEMPTS='1',MANAGEMENT_HEALTH_REDIS_ENABLED='false')
        args=['java','-Xms32m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(logs/(domain+'-service.jar'))]
        if legacy:args+=['--spring.flyway.target=5','--spring.jpa.hibernate.ddl-auto=none']
        handle=(logs/(name+'-'+secrets.token_hex(2)+'.log')).open('w',encoding='utf-8');handles.append(handle)
        process=subprocess.Popen(args,env=env,stdout=handle,stderr=subprocess.STDOUT);processes[name]=process
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            if process.poll() is not None:raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError(name+' readiness timeout')
    def stop(name,kill=False):
        process=processes[name]
        if process.poll() is not None:return
        process.kill() if kill else process.terminate()
        try:process.wait(10)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    def call(name,path,method='GET',body=None,token=operator,expected=200):return http.request(urls[name]+path,method,body,token=token,expected=expected)
    def status(order):return call('delivery-b',f'/deliveries/orders/{order}/synchronization')
    def reconcile(order):return call('delivery-b',f'/deliveries/orders/{order}/synchronization/reconcile','POST')
    def tracking(order,method='STANDARD_COURIER'):return call('delivery-b',f'/deliveries/orders/{order}/tracking?methodCode={method}','POST')
    def progress(order,state):return call('delivery-b',f'/deliveries/orders/{order}/tracking','PATCH',dict(status=state))
    def order(order_id):return call('order',f'/orders/{order_id}',token=customer)
    def purchased():return set(call('order','/internal/orders/customer/17/purchased-products?productIds=101,102,103,200,300,400,500,600,700,800,900,1000,1100',token=tokens['recommendation-service']))
    def review(order_id,product,expected=201):return call('review','/reviews','POST',dict(customerId=17,orderId=order_id,productId=product,rating=5,title='Audit review',content='Verified lifecycle purchase'),token=customer,expected=expected)
    def poll(action,predicate):
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            value=action()
            if predicate(value):return value
            time.sleep(.1)
        raise AssertionError('Recovery timed out')
    try:
        for domain in ['order','delivery','review']:shutil.copyfile(next((ROOT/(domain+'-service')/'target').glob('*-SNAPSHOT.jar')),logs/(domain+'-service.jar'))
        container=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',container,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PostgreSQL not ready')
        for domain in ['order','delivery','review']:http.command('docker','exec',container,'createdb','-U','audit',domain+'_db')
        start('order')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda name:start(name,legacy=name=='delivery-a'),['review','delivery-a']))
        sql('order',"""INSERT INTO orders(id,total_price,status,created_at,updated_at,inventory_reservation_id,customer_id,customer_email,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,idempotency_key,discount_amount,fulfillment_type)
            SELECT g,100,'PAID',now(),now(),g,17,'owner@example.test','Jan','Test','Street','00-001','City','PL','STANDARD_COURIER',0,'A17-'||g,0,'HOME_DELIVERY' FROM generate_series(1,11) g;
            INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) SELECT g,g*100,'A17-'||g,'Audit product',1,100,100 FROM generate_series(2,11) g;
            INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) VALUES (1,101,'A17-101','Product A',1,30,30),(1,102,'A17-102','Product B',1,30,30),(1,103,'A17-103','Product C',1,40,40);
            INSERT INTO order_sagas(order_id,state,attempt_count,created_at,updated_at) SELECT g,'COMPLETED',0,now(),now() FROM generate_series(1,11) g;
            UPDATE orders SET status='CONFIRMED',deferred_payment_id=92,deferred_payment_method='CASH_ON_DELIVERY' WHERE id=2;
            UPDATE orders SET delivery_method='STORE_PICKUP',fulfillment_type='STORE_PICKUP' WHERE id=3;
            UPDATE orders SET refund_payment_id=94,refund_state='REFUNDED',lifecycle_review_required=true WHERE id=4;
            UPDATE orders SET status='CANCELLED' WHERE id=5;
            UPDATE orders SET status='CONFIRMED' WHERE id=6;
            UPDATE order_sagas SET state='WAITING_FOR_PAYMENT' WHERE order_id IN (2,6);
            UPDATE order_sagas SET state='FAILED' WHERE order_id=5;""")
        sql('delivery',"INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,created_at,updated_at) VALUES (7,'MT-LEGACY-0007','MiniTech Courier','STANDARD_COURIER','DELIVERED',now(),now())")
        old=sql('delivery','SELECT tracking_number,status,updated_at FROM shipments WHERE order_id=7');stop('delivery-a')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(start,['delivery-a','delivery-b']))
        assert sql('delivery','SELECT tracking_number,status,updated_at FROM shipments WHERE order_id=7')==old
        assert status(7)['state']=='PENDING';assert reconcile(7)['state']=='SYNCED';assert order(7)['status']=='COMPLETED'
        from quality_gates import check_document
        expectation=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text())['services']['delivery']
        check_document(call('delivery-a','/v3/api-docs'),expectation,'Delivery A17')
        print('PASS: Delivery V5-to-V6 migration preserves carrier history and recovers legacy handover; Order V18 and OpenAPI validate',flush=True)
        before=purchased();assert {101,102,103}.issubset(before) and not {200,400,500,600}.intersection(before)
        initial_ranking={row['productId']:row['soldQuantity'] for row in call('order','/orders/bestsellers?limit=20',token=None)}
        review(1,101);track=tracking(1)
        payload=dict(trackingNumber=track['trackingNumber'],methodCode='STANDARD_COURIER',revision=1,status='IN_TRANSIT')
        for token,code in [(None,401),(customer,403),(operator,403),(tokens['payment-service'],403),(tokens['review-service'],403)]:
            call('order','/internal/orders/1/shipment-progress','POST',payload,token,code)
        for token,code in [(None,401),(customer,403),(tokens['delivery-service'],403)]:
            call('delivery-a','/deliveries/orders/1/synchronization',token=token,expected=code)
            call('delivery-a','/deliveries/orders/1/synchronization/reconcile','POST',token=token,expected=code)
        progress(1,'IN_TRANSIT');assert reconcile(1)['state']=='SYNCED';assert order(1)['status']=='SHIPPED';assert order(1)['paymentState']=='PAID';review(1,102)
        progress(1,'DELIVERED');assert reconcile(1)['state']=='SYNCED';assert order(1)['status']=='COMPLETED';review(1,103)
        assert purchased()==before
        assert {row['productId']:row['soldQuantity'] for row in call('order','/orders/bestsellers?limit=20',token=None)}==initial_ranking
        review(4,400,400);review(5,500,400);review(6,600,400)
        print('PASS: reviews, recommendation purchase IDs and bestseller quantities survive PAID-to-SHIPPED-to-COMPLETED; refunds and unpaid orders stay excluded',flush=True)
        tracking(2);progress(2,'DELIVERED');assert reconcile(2)['state']=='SYNCED'
        assert order(2)['status']=='COMPLETED' and order(2)['paymentState']=='COLLECTION_DUE';assert 200 not in purchased();review(2,200,400)
        call('order','/internal/orders/2/deferred/collect','POST',dict(paymentId=92,receiptReference='A17-COLLECTED',amount=100,currency='PLN'),tokens['payment-service'])
        assert order(2)['paymentState']=='PAID' and 200 in purchased();review(2,200)
        tracking(3,'STORE_PICKUP');progress(3,'PICKED_UP');assert reconcile(3)['state']=='SYNCED';assert order(3)['status']=='COMPLETED'
        tracking(4);progress(4,'IN_TRANSIT');assert reconcile(4)['state']=='REVIEW_REQUIRED';assert order(4)['paymentState']=='REFUNDED'
        tracking(6);progress(6,'IN_TRANSIT');assert reconcile(6)['state']=='RETRY';assert order(6)['status']=='CONFIRMED'
        print('PASS: handover never settles cash; only the Payment identity records collection; financial holds and unpaid card orders block advancement',flush=True)
        tracking(8);progress(8,'IN_TRANSIT');drop_once.add(8)
        assert reconcile(8)['state']=='RETRY';assert order(8)['status']=='SHIPPED'
        assert reconcile(8)['state']=='SYNCED';assert order(8)['status']=='SHIPPED'
        tracking(9);progress(9,'IN_TRANSIT');old_state=status(9);progress(9,'DELIVERED');new_state=status(9)
        def event(snapshot):return dict(trackingNumber=snapshot['trackingNumber'],methodCode='STANDARD_COURIER',revision=snapshot['revision'],status=snapshot['shipmentStatus'])
        call('order','/internal/orders/9/shipment-progress','POST',event(new_state),tokens['delivery-service'])
        assert call('order','/internal/orders/9/shipment-progress','POST',event(old_state),tokens['delivery-service'])['revision']==2
        assert order(9)['status']=='COMPLETED';assert reconcile(9)['state']=='SYNCED'
        call('delivery-b','/deliveries/orders/9/tracking','PATCH',dict(status='IN_TRANSIT'),expected=409)
        print('PASS: lost Order reply replays the same revision, and an old shipment fact never regresses a completed order',flush=True)
        tracking(10);progress(10,'IN_TRANSIT');pause_order=10
        def interrupted_sync():
            try:call('delivery-a','/deliveries/orders/10/synchronization/reconcile','POST')
            except (OSError,urllib.error.URLError):pass
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(interrupted_sync);assert committed.wait(15),'Order commit was not observed'
            assert order(10)['status']=='SHIPPED';stop('delivery-a',kill=True);release_reply.set();future.result(20)
        pause_order=None
        sql('delivery',"UPDATE shipments SET sync_until=now()-interval '1 second' WHERE order_id=10")
        assert reconcile(10)['state']=='SYNCED';assert order(10)['status']=='SHIPPED'
        start('delivery-a',recovery=True)
        tracking(11);drop_once.add(11);progress(11,'DELIVERED')
        poll(lambda:status(11),lambda value:value['state']=='SYNCED')
        assert order(11)['status']=='COMPLETED' and order(11)['paymentState']=='PAID'
        print('PASS: process death after Order commit recovers on the other Delivery replica; the background worker recovers a lost reply without operator input',flush=True)
        for domain in ['order','delivery','review']:
            assert sql(domain,f"SELECT count(*) FROM pg_stat_activity WHERE datname='{domain}_db' AND state='idle in transaction'")=='0'
        report.update(result='PASSED',httpChecks=http.CHECKS,deliveryReplicas=2,migrationFromV5=True,orderMigration=18,
            paidLifecycleStable=True,refundExcluded=True,cashCollectionSeparate=True,reviewLifecycle=True,legacyRecovery=True,
            lostReply=True,staleRevisionIgnored=True,crashAfterOrderCommit=True,scheduledRecovery=True,contractVerified=True)
        print('PASS A17: paid-purchase lifecycle and durable delivery-to-order synchronization',flush=True)
    finally:
        release_reply.set()
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        for name in ['delivery-a','delivery-b','review','order']:
            if name in processes:stop(name)
        for handle in handles:handle.close()
        for domain in ['order','delivery','review']:(logs/(domain+'-service.jar')).unlink(missing_ok=True)
        if container:subprocess.run(['docker','rm','-f',container],capture_output=True)
        fixture.shutdown();fixture.server_close();print('Isolated resources removed; logs:',logs,flush=True)

if __name__=='__main__':main()

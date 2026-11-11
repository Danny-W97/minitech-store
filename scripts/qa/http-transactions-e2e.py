#!/usr/bin/env python3
"""A31: real Order/Payment/Notification JARs, disposable PostgreSQL, one DB connection each.

Isolated HTTP/SMTP fixtures deliberately stall or trickle. No developer databases,
credentials, provider endpoints or application ports are used.
"""
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import socketserver
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('boundary_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec); spec.loader.exec_module(http)


def main():
    logs=ROOT/'tmp'/('http-transactions-'+secrets.token_hex(4)); logs.mkdir(parents=True)
    services=['order-service','payment-service','notification-service']
    tokens={name:secrets.token_urlsafe(32) for name in services}; operator=secrets.token_urlsafe(32)
    entered={name:threading.Event() for name in ['saga','payment','pdf','smtp']}
    released={name:threading.Event() for name in ['saga','payment','pdf','smtp']}; smtp_closed=threading.Event(); calls=[]
    processes=[]; handles=[]; container=None; report={}

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def body(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                chunks=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size: self.rfile.readline(); break
                    chunks.append(self.rfile.read(size)); self.rfile.read(2)
                raw=b''.join(chunks)
            else: raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
            return json.loads(raw) if raw else {}
        def reply(self,value,status=200):
            raw=json.dumps(value).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.end_headers()
            try: self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError): pass
        def do_POST(self):
            body=self.body()
            if self.path=='/internal/auth/introspect':
                actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
                if body.get('token')==operator: actor['role']='OPERATOR'
                for service,token in tokens.items():
                    if body.get('token')==token: actor.update(role='SERVICE',service=service)
                self.reply(actor)
            elif self.path=='/inventory/reservations':
                order=body['orderId']; calls.append(('reserve',order,self.headers.get('X-Request-Deadline-Ms')))
                if order==100: entered['saga'].set(); released['saga'].wait(8)
                self.reply(dict(reservationId=1000+order,orderId=order,status='RESERVED',expiresAt=None))
            else: self.reply({},404)
        def do_GET(self):
            if self.path=='/orders/200': self.reply(dict(id=200,items=[],totalPrice=10,status='CONFIRMED',inventoryReservationId=None,deliveryAddress=None,deliveryMethod='STANDARD_COURIER',deliveryCost=0,promotionCode=None,discountAmount=0,fulfillmentType='HOME_DELIVERY',deliveryDate=None,deliverySlot=None,pickupLocation=None,invoiceRequested=False,customerId=None,customerEmail='owner@example.test',lifecycleReviewRequired=False))
            elif self.path=='/invoices/1/pdf': entered['pdf'].set(); released['pdf'].wait(8); self.reply({})
            else: self.reply({},404)
        def do_PATCH(self):
            self.body(); calls.append(('payment',200,self.headers.get('X-Request-Deadline-Ms')))
            entered['payment'].set(); released['payment'].wait(8); self.reply({})

    class Smtp(socketserver.BaseRequestHandler):
        def handle(self):
            entered['smtp'].set()
            try:
                # More frequent than the idle timeout: only the whole-send budget stops this.
                for _ in range(80):
                    self.request.sendall(b'2'); time.sleep(.1)
                    if released['smtp'].is_set(): break
            except OSError: pass
            finally: smtp_closed.set()

    fixture=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
    smtp=socketserver.ThreadingTCPServer(('127.0.0.1',0),Smtp); smtp.daemon_threads=True
    threading.Thread(target=fixture.serve_forever,daemon=True).start(); threading.Thread(target=smtp.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(database,query):
        try: return http.command('docker','exec',container,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d',database+'_db','-Atc',query)
        except subprocess.CalledProcessError as error: raise RuntimeError(error.stderr) from error
    def get(url,**kwargs): return http.request(url,token=operator,**kwargs)
    def poll(action,condition,seconds=5):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            value=action()
            if condition(value): return value
            time.sleep(.03)
        raise AssertionError('Timed out waiting for state; last value: '+repr(value))
    try:
        password=secrets.token_urlsafe(24)
        container=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',container,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0: break
            time.sleep(1)
        else: raise RuntimeError('Temporary PostgreSQL not ready')
        urls={}
        for service in services:
            database=service.removesuffix('-service'); http.command('docker','exec',container,'createdb','-U','audit',database+'_db')
            with socket.socket() as probe: probe.bind(('127.0.0.1',0)); port=probe.getsockname()[1]
            env={k:v for k,v in os.environ.items() if not k.startswith(('PLATFORM_','CUSTOMER_','SPRING_','DB_','REDIS_','MAIL_','NOTIFICATION_','ORDER_','PAYMENT_','RESILIENCE_'))}
            env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=str(port),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}_db',DB_USERNAME='audit',DB_PASSWORD=password,
                PLATFORM_SERVICE_TOKEN=tokens[service],PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=fixture_url,
                REDIS_URL='redis://127.0.0.1:1',MANAGEMENT_HEALTH_REDIS_ENABLED='false',MANAGEMENT_HEALTH_MAIL_ENABLED='false',
                SPRING_DATA_REDIS_CONNECT_TIMEOUT='100ms',SPRING_DATA_REDIS_TIMEOUT='100ms',OUTBOX_PUBLISHER_FIXED_DELAY_MS='600000',
                SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='1',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
                # Flyway needs its own migration connection before the single-connection runtime test.
                SPRING_FLYWAY_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}_db',SPRING_FLYWAY_USER='audit',SPRING_FLYWAY_PASSWORD=password,
                ORDER_SAGA_RECOVERY_FIXED_DELAY_MS='200',RESILIENCE_HTTP_CONNECT_TIMEOUT_MS='100',RESILIENCE_HTTP_READ_TIMEOUT_MS='2000',
                RESILIENCE_RETRY_INITIAL_BACKOFF_MS='50',MAIL_HOST='127.0.0.1',MAIL_PORT=str(smtp.server_address[1]),
                NOTIFICATION_SMTP_READ_TIMEOUT_MS='1000',NOTIFICATION_SMTP_CONNECT_TIMEOUT_MS='1000',NOTIFICATION_CONSUMER_ENABLED='false',NOTIFICATION_RETRY_ENABLED='false')
            for downstream in http.OFFSETS:
                if downstream!='api-gateway': env[downstream.upper().replace('-','_')+'_URL']=fixture_url
            log=(logs/(service+'.log')).open('w'); handles.append(log)
            jar=next((ROOT/service/'target').glob('*-SNAPSHOT.jar'))
            process=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(jar)],env=env,stdout=log,stderr=subprocess.STDOUT)
            urls[service]=f'http://127.0.0.1:{port}'; processes.append((service,process))
        def ready(item):
            service,process=item; deadline=time.monotonic()+150
            while time.monotonic()<deadline:
                if process.poll() is not None: raise RuntimeError(service+' exited; see '+str(logs))
                try:
                    with urllib.request.urlopen(urls[service]+'/actuator/health',timeout=2) as response:
                        if response.status==200: return
                except (OSError,urllib.error.URLError): pass
                time.sleep(1)
            raise RuntimeError(service+' health timeout')
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: list(pool.map(ready,processes))
        print('READY: real services, one PostgreSQL connection per service',flush=True)
        sql('order',"INSERT INTO orders(id,customer_email,total_price,status,created_at,updated_at,idempotency_key,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,fulfillment_type) SELECT g,'owner@example.test',10,'CREATED',now(),now(),'boundary-'||g,'Jan','Nowak','Test 1','00-001','City','PL','STANDARD_COURIER',0,'HOME_DELIVERY' FROM generate_series(100,101) g; INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) SELECT g,1,'SKU','Product',1,10,10 FROM generate_series(100,101) g; INSERT INTO order_sagas(order_id,state,next_attempt_at,created_at,updated_at) SELECT g,'INVENTORY_RESERVATION_PENDING',now(),now(),now() FROM generate_series(100,101) g;")
        assert entered['saga'].wait(5)
        started=time.monotonic(); poll(lambda:sql('order','SELECT status FROM orders WHERE id=101'),lambda status:status=='CONFIRMED',1.7)
        report['fastSagaSeconds']=round(time.monotonic()-started,3)
        assert sql('order','SELECT processing_token IS NOT NULL FROM order_sagas WHERE order_id=100')=='t'
        before=time.monotonic(); assert len(get(urls['order-service']+'/orders'))==2
        assert time.monotonic()-before<1.5
        poll(lambda:sql('order','SELECT attempt_count FROM order_sagas WHERE order_id=100'),lambda count:int(count)>0,6)
        report['slowSagaBoundSeconds']=round(time.monotonic()-started,3)
        released['saga'].set()
        print('PASS: fast saga completed while slow saga held its lease; DB remained available',flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            began=time.monotonic()
            payment=pool.submit(get,urls['payment-service']+'/payments',method='POST',body=dict(orderId=200,paymentMethod='CARD',simulateFailure=False,invoiceRequested=False),headers={'X-Request-Deadline-Ms':str(int(time.time()*1000)+1200)},expected=503)
            assert entered['payment'].wait(2)
            before=time.monotonic(); assert len(get(urls['payment-service']+'/payments'))==1
            assert time.monotonic()-before<.9 and not payment.done()
            payment.result(timeout=4); report['paymentDeadlineSeconds']=round(time.monotonic()-began,3)
            assert report['paymentDeadlineSeconds']<3
            assert sql('payment','SELECT processing_token IS NULL FROM payments WHERE order_id=200')=='t'
            assert len([call for call in calls if call[0]=='payment'])==1
            released['payment'].set()
            began=time.monotonic()
            notification=dict(eventType='PAYMENT_SUCCEEDED',sourceId=1,recipient='owner@example.test',channel='EMAIL',subject='Audit',message='Audit',simulateFailure=False,invoiceId=1)
            delivery=pool.submit(get,urls['notification-service']+'/notifications',method='POST',body=notification,expected=503,headers={'X-Request-Deadline-Ms':str(int(time.time()*1000)+1200)})
            assert entered['pdf'].wait(2)
            before=time.monotonic(); assert len(get(urls['notification-service']+'/notifications'))==1
            assert time.monotonic()-before<.9 and not delivery.done()
            assert delivery.result(timeout=4)['status']=='FAILED'; report['pdfDeadlineSeconds']=round(time.monotonic()-began,3)
            assert report['pdfDeadlineSeconds']<3
            released['pdf'].set()
            began=time.monotonic()
            notification.update(sourceId=2,invoiceId=None)
            delivery=pool.submit(get,urls['notification-service']+'/notifications',method='POST',body=notification,expected=503,headers={'X-Request-Deadline-Ms':str(int(time.time()*1000)+1200)})
            assert entered['smtp'].wait(2)
            assert len(get(urls['notification-service']+'/notifications'))==2 and not delivery.done()
            assert delivery.result(timeout=4)['status']=='FAILED'; report['smtpDeadlineSeconds']=round(time.monotonic()-began,3)
            assert report['smtpDeadlineSeconds']<3 and smtp_closed.wait(2)
        assert all(call[2] for call in calls)
        assert sql('notification',"SELECT count(*) FROM pg_stat_activity WHERE datname IN ('order_db','payment_db','notification_db') AND state='idle in transaction'")=='0'
        (logs/'report.json').write_text(json.dumps(report,indent=2))
        print('PASS: request budgets stop payment/PDF/SMTP stalls, retries stay bounded and DB connections are released:',json.dumps(report),flush=True)
    finally:
        for gate in released.values(): gate.set()
        for _,process in processes: process.terminate()
        for _,process in processes:
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for handle in handles: handle.close()
        if container: subprocess.run(['docker','rm','-f',container],capture_output=True)
        fixture.shutdown(); fixture.server_close(); smtp.shutdown(); smtp.server_close()
        print('Isolated resources removed; logs:',logs,flush=True)


if __name__=='__main__': main()

#!/usr/bin/env python3
"""A05: real Order/Inventory, two Payment JVMs, disposable PostgreSQL and a local Stripe protocol fixture.

No developer vault, real Stripe account, or external payment is used.
"""
import base64
import concurrent.futures
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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
import urllib.parse
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('stripe_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)


def main():
    logs=ROOT/'tmp'/('stripe-payments-'+secrets.token_hex(4));logs.mkdir(parents=True)
    nodes=['order','inventory','payment-a','payment-b'];processes={};handles=[];postgres=None
    password=secrets.token_urlsafe(24);operator=secrets.token_urlsafe(32);customer=secrets.token_urlsafe(32)
    tokens={name:secrets.token_urlsafe(32) for name in ['order-service','inventory-service','payment-service']}
    webhook_secret='whsec_'+secrets.token_hex(20);api_key='sk_test_local_protocol_fixture'
    ports={};urls={};sessions={};refunds={};creation_keys={};refund_keys={};money_moves={};post_counts={}
    drop_once=set();pending_refunds=set();mutex=threading.RLock();report={'result':'FAILED','externalStripeSandbox':False}
    for name in nodes:
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));ports[name]=probe.getsockname()[1]
        urls[name]=f'http://127.0.0.1:{ports[name]}'

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def reply(self,data,status=200):
            raw=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json')
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
                return b''.join(parts)
            return self.rfile.read(int(self.headers.get('Content-Length','0')))
        def stripe_auth(self):
            expected='Basic '+base64.b64encode((api_key+':').encode()).decode()
            assert self.headers.get('Authorization')==expected,'Stripe received an unexpected credential'
            assert not any('service-token' in name.lower() for name in self.headers),'Internal token leaked to Stripe'
        def do_GET(self):
            path=urllib.parse.urlsplit(self.path)
            if path.path=='/internal/products/batch':
                ids=urllib.parse.parse_qs(path.query)['ids'][0].split(',')
                self.reply([dict(id=int(value),sku='SKU-'+value,name='Audit product',category='Test',manufacturer='Test',model='Test',
                    technicalParameters={},price=10,regularPrice=10,discountPercent=0,promotional=False,newProduct=False,
                    rotationDate='2026-10-25',active=True,createdAt='2026-10-25T00:00:00Z',updatedAt='2026-10-25T00:00:00Z') for value in ids]);return
            if path.path.startswith('/v1/'):
                self.stripe_auth()
                with mutex:
                    if path.path.startswith('/v1/checkout/sessions/'):self.reply(sessions[path.path.rsplit('/',1)[1]]);return
                    if path.path.startswith('/v1/payment_intents/'):
                        intent=path.path.rsplit('/',1)[1];session=next(row for row in sessions.values() if row['payment_intent']==intent)
                        self.reply(dict(id=intent,object='payment_intent',livemode=False,metadata=session['metadata']));return
                    if path.path.startswith('/v1/refunds/'):self.reply(refunds[path.path.rsplit('/',1)[1]]);return
                    if path.path=='/v1/refunds':
                        intent=urllib.parse.parse_qs(path.query)['payment_intent'][0]
                        self.reply(dict(data=[row for row in refunds.values() if row['payment_intent']==intent],has_more=False));return
            self.reply({},404)
        def do_POST(self):
            path=urllib.parse.urlsplit(self.path).path;raw=self.body()
            if path.startswith('/v1/'):
                self.stripe_auth();form={key:values[0] for key,values in urllib.parse.parse_qs(raw.decode()).items()}
                with mutex:
                    key=self.headers.get('Idempotency-Key');assert key
                    if path=='/v1/checkout/sessions':
                        if key in creation_keys:
                            old,sid=creation_keys[key]
                            if old!=form:self.reply({'error':{'type':'idempotency_error'}},400);return
                        else:
                            sid='cs_test_'+uuid.uuid4().hex;intent='pi_test_'+uuid.uuid4().hex
                            sessions[sid]=dict(id=sid,object='checkout.session',mode='payment',livemode=False,
                                amount_total=int(form['line_items[0][price_data][unit_amount]']),currency='pln',payment_intent=intent,
                                payment_status='unpaid',status='open',client_reference_id=form['client_reference_id'],
                                metadata=dict(order_id=form['metadata[order_id]']),url='https://checkout.stripe.com/c/pay/'+sid)
                            creation_keys[key]=(form,sid)
                        self.reply(sessions[sid]);return
                    if path=='/v1/refunds':
                        intent=form['payment_intent'];post_counts[intent]=post_counts.get(intent,0)+1
                        if key in refund_keys:rid=refund_keys[key]
                        else:
                            rid='re_test_'+uuid.uuid4().hex;refund_keys[key]=rid;money_moves[intent]=money_moves.get(intent,0)+1
                            refunds[rid]=dict(id=rid,object='refund',payment_intent=intent,amount=int(form['amount']),currency='pln',
                                status='pending' if intent in pending_refunds else 'succeeded',metadata=dict(operation_id=form['metadata[operation_id]']))
                        if intent in drop_once:
                            drop_once.remove(intent);self.close_connection=True
                            try:self.connection.shutdown(socket.SHUT_RDWR)
                            except OSError:pass
                            return
                        self.reply(refunds[rid]);return
                self.reply({},404);return
            if path=='/deliveries/quote':
                self.reply(dict(methodCode='STANDARD_COURIER',deliveryCost=0,fulfillmentType='HOME_DELIVERY',selectedDate='2026-10-30'));return
            if path.startswith('/deliveries/orders/'):
                # Optional tracking is deliberately unavailable so the durable task remains visible.
                self.reply({},503);return
            data=json.loads(raw or b'{}');actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
            if data.get('token')==operator:actor.update(role='OPERATOR',email='operator@example.test')
            if data.get('token')==customer:actor.update(role='CUSTOMER',customerId=17,email='owner@example.test')
            for service,token in tokens.items():
                if data.get('token')==token:actor.update(role='SERVICE',service=service)
            self.reply(actor)

    class FixtureServer(ThreadingHTTPServer):
        request_queue_size=128
        daemon_threads=True
    fixture=FixtureServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(domain,query):return http.command('docker','exec',postgres,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d',domain+'_db','-Atqc',query)
    def poll(action,predicate,seconds=60):
        deadline=time.monotonic()+seconds;value=None
        while time.monotonic()<deadline:
            value=action()
            if predicate(value):return value
            time.sleep(.1)
        raise AssertionError('Timeout: '+repr(value))
    def start(name,recovery=False):
        domain='payment' if name.startswith('payment') else name;module=domain+'-service'
        env={key:value for key,value in os.environ.items() if not key.startswith(('SPRING_','PLATFORM_','DB_','PAYMENT_','STRIPE_','OUTBOX_','REDIS_','RESILIENCE_','INVENTORY_','ORDER_'))}
        env.update(SPRING_PROFILES_ACTIVE='test',SERVER_PORT=str(ports[name]),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{domain}_db?ApplicationName={name}',
            DB_USERNAME='audit',DB_PASSWORD=password,PLATFORM_SERVICE_TOKEN=tokens[module],PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=urls['order'],
            ORDER_SERVICE_URL=urls['order'],INVENTORY_SERVICE_URL=urls['inventory'],PAYMENT_SERVICE_URL=urls['payment-b'],PRODUCT_SERVICE_URL=fixture_url,
            DELIVERY_SERVICE_URL=fixture_url,PROMOTION_SERVICE_URL=fixture_url,BILLING_SERVICE_URL=fixture_url,
            PAYMENT_PROVIDER='stripe',STRIPE_SECRET_KEY=api_key,STRIPE_WEBHOOK_SECRET=webhook_secret,STRIPE_API_BASE_URL=fixture_url,
            PAYMENT_RETURN_BASE_URL='http://127.0.0.1:18070/index.html',STRIPE_RECOVERY_ENABLED='true' if recovery else 'false',STRIPE_RECOVERY_DELAY_MS='200',
            REDIS_URL='redis://127.0.0.1:1',MANAGEMENT_HEALTH_REDIS_ENABLED='false',OUTBOX_PUBLISHER_ENABLED='false',
            PAYMENT_FOLLOW_UPS_ENABLED='false',INVENTORY_RESERVATION_TTL_SECONDS='600',INVENTORY_RESERVATION_CLEANUP_FIXED_DELAY_MS='600000',
            ORDER_SAGA_RECOVERY_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
            RESILIENCE_RETRY_MAX_ATTEMPTS='1',RESILIENCE_CB_MINIMUM_NUMBER_OF_CALLS='1000',RESILIENCE_CB_SLIDING_WINDOW_SIZE='1000')
        handle=(logs/(name+'-'+secrets.token_hex(2)+'.log')).open('w',encoding='utf-8');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms32m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(logs/(module+'.jar'))],env=env,stdout=handle,stderr=subprocess.STDOUT)
    def ready(name):
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(name+' exited: '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as result:
                    if result.status==200:return
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError('Readiness timeout '+name)
    def call(name,path,method='GET',body=None,token=operator,expected=200):return http.request(urls[name]+path,method,body,token=token,expected=expected)
    def create():
        order=call('order','/orders','POST',dict(items=[dict(productId=1,quantity=1)],customerId=17,customerEmail='owner@example.test',
            deliveryAddress=dict(firstName='Jan',lastName='Audit',street='Street',postalCode='00-001',city='City',country='PL'),
            deliveryMethod='STANDARD_COURIER',invoiceRequested=False),token=customer,expected=201)
        assert order['status']=='CONFIRMED',order
        key='stripe-'+uuid.uuid4().hex
        body=dict(orderId=order['id'],paymentMethod='CARD',customerId=17,simulateFailure=False,invoiceRequested=False,idempotencyKey=key)
        payment=call('payment-a','/payments','POST',body,token=customer,expected=201)
        assert payment['status']=='PENDING' and payment['checkoutUrl'].startswith('https://checkout.stripe.com/')
        assert call('payment-b','/payments','POST',body,token=customer,expected=201)['id']==payment['id']
        sid=sql('payment',f"SELECT checkout_session_id FROM payments WHERE id={payment['id']}")
        return order,payment,sid
    def paid(sid):
        with mutex:sessions[sid].update(payment_status='paid',status='complete')
    def webhook(sid,name='payment-a',expected=200,event_id=None,bad_signature=False):
        with mutex:event=dict(id=event_id or 'evt_'+uuid.uuid4().hex,type='checkout.session.completed',livemode=False,data=dict(object=dict(sessions[sid])))
        return provider_event(event,name,expected,bad_signature)
    def provider_event(event,name='payment-a',expected=200,bad_signature=False):
        stamp=int(time.time());signature=hmac.new(webhook_secret.encode(),str(stamp).encode()+b'.'+json.dumps(event).encode(),hashlib.sha256).hexdigest()
        return http.request(urls[name]+'/payments/webhooks/stripe','POST',event,headers={'Stripe-Signature':f't={stamp},v1='+('0'*64 if bad_signature else signature)},expected=expected)
    def refund_row(payment):return call('payment-b',f'/payments/{payment["id"]}/refunds')[0]
    def release(order):call('inventory',f'/inventory/reservations/{order["inventoryReservationId"]}/release','POST',token=tokens['order-service'])
    def stop(name):
        if processes[name].poll() is None:processes[name].kill();processes[name].wait(10)
    try:
        for domain in ['order','inventory','payment']:
            shutil.copyfile(next((ROOT/(domain+'-service')/'target').glob('*-SNAPSHOT.jar')),logs/(domain+'-service.jar'))
        postgres=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PostgreSQL not ready')
        for domain in ['order','inventory','payment']:http.command('docker','exec',postgres,'createdb','-U','audit',domain+'_db')
        for name in nodes:start(name)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(ready,nodes))
        sql('inventory','UPDATE inventory_items SET available_quantity=1000,reserved_quantity=0,sold_quantity=0 WHERE product_id=1')
        print('READY: real Order/Inventory, two Stripe Payment replicas, isolated PostgreSQL and local provider fixture',flush=True)

        order,payment,sid=create();paid(sid)
        webhook(sid,bad_signature=True,expected=400)
        assert call('payment-b',f'/payments/{payment["id"]}',token=customer)['status']=='PENDING'
        event='evt_'+uuid.uuid4().hex;webhook(sid,event_id=event);webhook(sid,name='payment-b',event_id=event)
        assert call('payment-b',f'/payments/{payment["id"]}',token=customer)['status']=='SUCCEEDED'
        assert call('order',f'/orders/{order["id"]}')['status']=='PAID'
        for actor,status in [(None,401),(customer,403),(tokens['order-service'],403)]:
            call('payment-b',f'/payments/{payment["id"]}/refunds','POST',dict(amount=payment['amount'],idempotencyKey=uuid.uuid4().hex,reason='CUSTOMER_REQUEST'),token=actor,expected=status)
        call('payment-b',f'/payments/{payment["id"]}/refunds','POST',dict(amount=1,idempotencyKey=uuid.uuid4().hex,reason='CUSTOMER_REQUEST'),expected=409)
        body=dict(amount=payment['amount'],idempotencyKey=uuid.uuid4().hex,reason='CUSTOMER_REQUEST')
        refunded=call('payment-a',f'/payments/{payment["id"]}/refunds','POST',body)
        assert refunded['status']=='SUCCEEDED' and refunded['businessOutcome']=='REFUNDED_ORDER_REVIEW_REQUIRED',refunded
        assert call('payment-b',f'/payments/{payment["id"]}/refunds','POST',body)['id']==refunded['id']
        held=call('order',f'/orders/{order["id"]}');assert held['paymentState']=='REFUNDED' and held['lifecycleReviewRequired']
        call('order',f'/orders/{order["id"]}/status','PATCH',dict(status='SHIPPED'),expected=409)
        print('PASS: signed/duplicate webhook, operator-only full refund, immutable intent and fulfillment hold',flush=True)

        order,payment,sid=create();paid(sid);intent=sessions[sid]['payment_intent'];rid='re_external_'+uuid.uuid4().hex
        with mutex:
            refunds[rid]=dict(id=rid,object='refund',payment_intent=intent,amount=sessions[sid]['amount_total'],currency='pln',status='succeeded',metadata={})
            provider=dict(refunds[rid])
        provider_event(dict(id='evt_'+uuid.uuid4().hex,type='refund.created',livemode=False,data=dict(object=provider)))
        webhook(sid,name='payment-b')
        observed=call('payment-b',f'/payments/{payment["id"]}/stripe-status')
        assert observed['payment']['status']=='REFUNDED' and observed['externalRefundId']==rid,observed
        held=call('order',f'/orders/{order["id"]}')
        assert held['status']=='CONFIRMED' and held['paymentState']=='REFUNDED' and held['lifecycleReviewRequired'],held
        assert call('payment-b',f'/payments/{payment["id"]}/refunds')==[]
        assert post_counts.get(intent,0)==0 and money_moves.get(intent,0)==0
        call('order',f'/orders/{order["id"]}/status','PATCH',dict(status='SHIPPED'),expected=409)
        print('PASS: external Dashboard refund received before paid webhook fences fulfillment without another refund POST',flush=True)

        order,payment,sid=create();paid(sid);webhook(sid)
        call('order',f'/orders/{order["id"]}/status','PATCH',dict(status='SHIPPED'))
        before=sql('payment',f"SELECT string_agg(kind||':'||status,',' ORDER BY kind) FROM payment_follow_ups WHERE order_id={order['id']}")
        rejected=call('payment-a',f'/payments/{payment["id"]}/refunds','POST',dict(amount=payment['amount'],idempotencyKey=uuid.uuid4().hex,reason='CUSTOMER_REQUEST'))
        assert rejected['status']=='FAILED' and rejected['businessOutcome']=='REJECTED_NO_MONEY_MOVED',rejected
        assert sql('payment',f"SELECT string_agg(kind||':'||status,',' ORDER BY kind) FROM payment_follow_ups WHERE order_id={order['id']}")==before
        assert sessions[sid]['payment_intent'] not in money_moves
        print('PASS: ineligible shipped-order refund moves no money and leaves purchase tasks intact',flush=True)

        order,payment,sid=create();release(order);paid(sid);webhook(sid)
        row=refund_row(payment);assert row['status']=='SUCCEEDED' and row['businessOutcome']=='CANCELLED_AFTER_REFUND',row
        assert call('order',f'/orders/{order["id"]}')['status']=='CANCELLED'
        assert money_moves[sessions[sid]['payment_intent']]==1
        print('PASS: paid Checkout after released stock is refunded and compensated without a false PAID order',flush=True)

        order,payment,sid=create();release(order);paid(sid);intent=sessions[sid]['payment_intent'];drop_once.add(intent);webhook(sid)
        row=refund_row(payment);assert row['status']=='REQUESTED',row
        row=call('payment-b',f'/payments/refunds/{row["id"]}/reconcile','POST')
        assert row['status']=='SUCCEEDED' and row['businessOutcome']=='CANCELLED_AFTER_REFUND',row
        assert post_counts[intent]==1 and money_moves[intent]==1
        print('PASS: lost refund response is found by operation metadata; second replica never sends another refund',flush=True)

        order,payment,sid=create();release(order);paid(sid);intent=sessions[sid]['payment_intent'];pending_refunds.add(intent);webhook(sid)
        row=refund_row(payment);assert row['status']=='PENDING'
        assert call('payment-b',f'/payments/{payment["id"]}')['status']=='PENDING'
        with mutex:refunds[row['providerRefundId']]['status']='succeeded';provider=dict(refunds[row['providerRefundId']])
        provider_event(dict(id='evt_'+uuid.uuid4().hex,type='refund.updated',livemode=False,data=dict(object=provider)),name='payment-b')
        assert refund_row(payment)['businessOutcome']=='CANCELLED_AFTER_REFUND'
        assert post_counts[intent]==1
        print('PASS: pending refund stays pending until canonical provider confirmation arrives through the signed webhook',flush=True)

        order,payment,sid=create()
        with mutex:sessions[sid]['status']='expired';expired=dict(sessions[sid])
        provider_event(dict(id='evt_'+uuid.uuid4().hex,type='checkout.session.expired',livemode=False,data=dict(object=expired)))
        assert call('payment-b',f'/payments/{payment["id"]}')['status']=='FAILED'
        assert call('order',f'/orders/{order["id"]}')['status']=='CANCELLED'
        print('PASS: unpaid expiry durably cancels the order and releases its stock',flush=True)

        order,payment,sid=create();paid(sid);source=order['id']
        sql('payment',f"CREATE FUNCTION pause_stripe_finish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.order_id={source} AND NEW.status='SUCCEEDED' THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_stripe_finish BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION pause_stripe_finish()")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(webhook,sid)
            poll(lambda:sql('payment',"SELECT count(*) FROM pg_stat_activity WHERE datname='payment_db' AND wait_event='PgSleep'"),lambda count:count!='0')
            assert call('order',f'/orders/{source}')['status']=='PAID';stop('payment-a')
            sql('payment',"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='payment_db' AND wait_event='PgSleep'")
            sql('payment','DROP TRIGGER pause_stripe_finish ON payments; DROP FUNCTION pause_stripe_finish()')
            try:future.result(30)
            except (OSError,urllib.error.URLError):pass
        sql('payment',f"UPDATE payments SET processing_until=now()-interval '1 second' WHERE id={payment['id']}")
        assert call('payment-b',f'/payments/{payment["id"]}/reconcile','POST')['payment']['status']=='SUCCEEDED'
        assert sql('payment',f"SELECT count(*) FROM payment_follow_ups WHERE order_id={source}")=='2'
        start('payment-a');ready('payment-a')
        print('PASS: process death after Order PAID recovers on the other replica without another charge or duplicated tasks',flush=True)

        order,payment,sid=create();release(order);paid(sid);source=order['id'];intent=sessions[sid]['payment_intent']
        sql('order',f"CREATE FUNCTION pause_refund_complete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id={source} AND NEW.refund_state='REFUNDED' THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_refund_complete BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION pause_refund_complete()")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(webhook,sid)
            poll(lambda:sql('order',"SELECT count(*) FROM pg_stat_activity WHERE datname='order_db' AND wait_event='PgSleep'"),lambda count:count!='0')
            assert call('payment-b',f'/payments/{payment["id"]}')['status']=='REFUNDED';stop('payment-a')
            sql('order',"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='order_db' AND wait_event='PgSleep'")
            sql('order','DROP TRIGGER pause_refund_complete ON orders; DROP FUNCTION pause_refund_complete()')
            try:future.result(30)
            except (OSError,urllib.error.URLError):pass
        row=refund_row(payment);assert row['status']=='SUCCEEDED' and row['businessOutcome'] is None
        sql('payment',f"UPDATE payment_refunds SET processing_until=now()-interval '1 second' WHERE id={row['id']}")
        row=call('payment-b',f'/payments/refunds/{row["id"]}/reconcile','POST')
        assert row['businessOutcome']=='CANCELLED_AFTER_REFUND' and post_counts[intent]==1 and money_moves[intent]==1,row
        start('payment-a');ready('payment-a')
        print('PASS: process death after the refund retries only stock/order completion, never the money transfer',flush=True)

        order,payment,sid=create();paid(sid)
        def raced(index):
            try:webhook(sid,name='payment-a' if index%2 else 'payment-b');return 200
            except AssertionError as error:
                if 'got 409;' not in str(error):raise
                return 409
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:results=list(pool.map(raced,range(6)))
        assert 200 in results
        assert call('payment-b',f'/payments/{payment["id"]}')['status']=='SUCCEEDED'
        assert sql('inventory',f"SELECT status FROM inventory_reservations WHERE order_id={order['id']}")=='CONFIRMED'
        assert sql('payment',f"SELECT count(*) FROM payment_follow_ups WHERE order_id={order['id']}")=='2'
        print('PASS: six concurrent provider events across both Payment replicas have one settlement',flush=True)

        stop('payment-b');start('payment-b',recovery=True);ready('payment-b')
        order,payment,sid=create();paid(sid)
        sql('payment',f"UPDATE payments SET stripe_next_attempt_at=now() WHERE id={payment['id']}")
        poll(lambda:call('payment-b',f'/payments/{payment["id"]}'),lambda row:row['status']=='SUCCEEDED')
        assert sql('payment',f"SELECT count(*) FROM stripe_webhook_receipts WHERE payment_id={payment['id']}")=='0'
        order,payment,sid=create();release(order);paid(sid);intent=sessions[sid]['payment_intent'];drop_once.add(intent);webhook(sid)
        row=poll(lambda:refund_row(payment),lambda row:row['businessOutcome']=='CANCELLED_AFTER_REFUND')
        assert row['status']=='SUCCEEDED' and post_counts[intent]==1 and money_moves[intent]==1
        print('PASS: background workers settle a paid session with no webhook and recover a lost refund response without operator input',flush=True)

        for domain in ['payment','order','inventory']:
            assert sql(domain,f"SELECT count(*) FROM pg_stat_activity WHERE datname='{domain}_db' AND state='idle in transaction'")=='0'
        assert all(count==1 for count in money_moves.values()),money_moves
        report.update(result='PASSED',paymentReplicas=2,httpChecks=http.CHECKS,refundTransfers=len(money_moves),
            duplicateWebhook=True,refundResponseLoss=True,crashAfterOrderPaid=True,crashAfterRefund=True,
            pendingRefundWebhook=True,unpaidExpiry=True,rejectedRefundPreservesTasks=True,externalRefundObserved=True,concurrentEvents=6,financialHold=True,scheduledRecovery=True)
        print('PASS A05: hosted-session protocol, signed webhooks, durable full refunds and replica crash recovery',flush=True)
    finally:
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        for process in processes.values():
            if process.poll() is None:process.terminate()
        for process in processes.values():
            try:process.wait(12)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for handle in handles:handle.close()
        for domain in ['order','inventory','payment']:(logs/(domain+'-service.jar')).unlink(missing_ok=True)
        if postgres:subprocess.run(['docker','rm','-f',postgres],capture_output=True)
        fixture.shutdown();fixture.server_close();print('Isolated resources removed; logs:',logs,flush=True)


if __name__=='__main__':main()

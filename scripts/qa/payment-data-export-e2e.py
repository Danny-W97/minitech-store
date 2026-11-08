#!/usr/bin/env python3
"""A43.24: owner-only stored payment and refund export with isolated Customer/Order/Delivery/Gateway."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import quality_gates as qa

ROOT = Path(__file__).resolve().parents[2]


def command(*args):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f'Fixture command failed: {args[0]} {args[1] if len(args)>1 else ""}')
    return result.stdout.strip()


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def main():
    directory = ROOT / 'tmp' / ('payment-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = {}, [], []
    password, operator = (secrets.token_urlsafe(32) for _ in range(2))
    credentials = {name: secrets.token_urlsafe(32) for name in ('order-service', 'payment-service')}
    signing_key = secrets.token_bytes(48)
    names = ('customer-service', 'order-service', 'payment-service', 'api-gateway')
    urls = {name: f'http://127.0.0.1:{port()}' for name in names}

    def sql(database, query):
        return command('docker', 'exec', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'fixture', '-d', database, '-Atqc', query)

    def token(customer=1, email='owner@fixture.example', expired=False):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode(
            {'typ': 'access', 'sub': str(customer), 'email': email, 'exp': int(time.time()) + (-600 if expired else 600)})
        return unsigned + '.' + base64.urlsafe_b64encode(hmac.new(signing_key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

    provider_calls = []
    class ProviderProbe(BaseHTTPRequestHandler):
        def do_GET(self):
            provider_calls.append(self.command)
            self.send_response(503); self.end_headers()
        do_POST = do_GET
        def log_message(self, *args):
            pass
    provider = ThreadingHTTPServer(('127.0.0.1', 0), ProviderProbe)
    provider_thread = threading.Thread(target=provider.serve_forever, daemon=True)
    provider_thread.start()
    try:
        postgres = command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432',
                           '-e', 'POSTGRES_USER=fixture', '-e', 'POSTGRES_PASSWORD=' + password, 'postgres:16-alpine')
        db_port = command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'fixture'], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Fixture PostgreSQL startup timed out')
        for database in ('customer_db', 'order_db', 'payment_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in names:
            database = {'order-service': 'order_db', 'payment-service': 'payment_db'}.get(name, 'customer_db')
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       ORDER_SERVICE_URL=urls['order-service'], PLATFORM_ORDER_URL=urls['order-service'],
                       PAYMENT_SERVICE_URL=urls['payment-service'], PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=credentials.get(name, secrets.token_urlsafe(32)),
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
                       CUSTOMER_JWT_SECRET=base64.b64encode(signing_key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       ORDER_SAGA_RECOVERY_ENABLED='false', COUPON_EXPIRY_ENABLED='false', PAYMENT_PROVIDER='disabled', STRIPE_RECOVERY_ENABLED='false',
                       PAYMENT_FOLLOW_UPS_ENABLED='false', OUTBOX_PUBLISHER_ENABLED='false', PAYMENT_OUTBOX_RETENTION_MODE='off',
                       STRIPE_API_BASE_URL=f'http://127.0.0.1:{provider.server_port}',
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {name} first'
            handle = (directory / (name + '.log')).open('w'); handles.append(handle)
            process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                       env=env, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
            processes[name] = process
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'{name} exited; inspect fixture logs in {directory}')
                if qa.Client(urls[name], timeout=2).get('/actuator/health').status == 200:
                    break
                time.sleep(.3)
            else:
                raise RuntimeError(f'{name} fixture readiness timed out')


        sql('customer_db', """
            INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            VALUES ('owner@fixture.example','fixture-hash','Jan','Testowy','Testowa 1','00-001','Warszawa','Polska',true,now(),now()),
                   ('other@fixture.example','fixture-hash','Anna','Inna','Testowa 2','00-002','Kraków','Polska',true,now(),now()),
                   ('disabled@fixture.example','fixture-hash','Nie','Aktywna','Testowa 3','00-003','Kraków','Polska',false,now(),now())
            """)
        sql('order_db', """
            INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              fulfillment_type,idempotency_key,guest_access_token_hash,created_at,updated_at)
            SELECT n,CASE WHEN n=2 THEN 2 WHEN n=3 THEN NULL ELSE 1 END,'owner@fixture.example',123.45,'PAID',
              'Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,'HOME_DELIVERY',
              'fixture-'||n,repeat('a',64),now(),now() FROM generate_series(1,9) n;
            SELECT setval('orders_id_seq',9)
            """)
        guest_capability = 'G' * 43
        sql('order_db', "UPDATE orders SET guest_access_token_hash='" + hashlib.sha256(guest_capability.encode()).hexdigest() + "' WHERE id=3")
        sql('payment_db', """
            INSERT INTO payments(id,order_id,idempotency_key,amount,currency,payment_method,status,provider_reference,failure_reason,
              checkout_session_id,checkout_url,stripe_intent_id,stripe_error,processing_token,processing_until,collected_at,created_at,updated_at)
            SELECT n,n,'fixture-private-idempotency-'||n,123.45,'PLN',CASE WHEN n=8 THEN 'CASH_AT_PICKUP' ELSE 'CARD' END,
              CASE WHEN n=5 THEN 'PENDING' WHEN n=6 THEN 'FAILED' WHEN n=7 THEN 'REFUNDED' ELSE 'SUCCEEDED' END,
              'fixture-private-provider-'||n,'fixture-private-failure','cs_fixture_private_'||n,'https://fixture.invalid/private-checkout',
              'pi_fixture_private_'||n,'fixture-private-stripe','fixture-private-token-'||n,now()+interval '1 hour',
              CASE WHEN n=8 THEN '2025-01-02T03:04:05Z'::timestamptz ELSE NULL END,now(),now()
            FROM generate_series(1,9) n WHERE n<>4;
            SELECT setval('payments_id_seq',9);
            INSERT INTO payment_refunds(id,payment_id,order_id,request_key,operation_id,amount,currency,intent_id,provider_refund_id,
              status,automatic,reason,requested_by,last_error,processing_token,processing_until,submitted_at,created_at,updated_at)
            SELECT n,n,CASE WHEN n=9 THEN 2 ELSE n END,'fixture-private-request-'||n,gen_random_uuid()::text,123.45,'PLN',
              'pi_fixture_private_'||n,'re_fixture_private_'||n,
              CASE WHEN n=2 THEN 'FAILED' WHEN n IN (3,7) THEN 'SUCCEEDED' ELSE 'PENDING' END,
              false,'OPERATOR_RECONCILIATION','private-operator@fixture.example','fixture-private-error',
              'fixture-private-refund-token-'||n,now()+interval '1 hour',
              CASE WHEN n=7 THEN '2025-01-03T04:05:06Z'::timestamptz ELSE NULL END,now(),now()
            FROM generate_series(1,9) n WHERE n IN (1,2,3,7,9);
            SELECT setval('payment_refunds_id_seq',9);
            INSERT INTO payment_order_gates(order_id,cancelled) SELECT n,false FROM generate_series(1,9) n;
            INSERT INTO stripe_webhook_receipts(event_id,event_type,payment_id,outcome,received_at)
            VALUES('evt_fixture_private','checkout.session.completed',1,'PROCESSED',now()),
                  ('evt_fixture_private_unlinked','unknown',NULL,'IGNORED',now())
            """)
        payment_snapshot="SELECT json_build_array((SELECT json_agg(p ORDER BY id) FROM payments p),(SELECT json_agg(r ORDER BY id) FROM payment_refunds r),(SELECT json_agg(g ORDER BY order_id) FROM payment_order_gates g),(SELECT json_agg(o ORDER BY id) FROM payment_outbox_events o),(SELECT json_agg(f ORDER BY id) FROM payment_follow_ups f),(SELECT json_agg(w ORDER BY event_id) FROM stripe_webhook_receipts w))::text"
        order_snapshot='SELECT json_agg(o ORDER BY id)::text FROM orders o'
        before=sql('payment_db',payment_snapshot),sql('order_db',order_snapshot)
        payment_fields={'paymentId','orderId','amount','currency','paymentMethod','status','collectedAt','createdAt','updatedAt'}
        refund_fields={'refundId','paymentId','orderId','amount','currency','status','submittedAt','createdAt','updatedAt'}
        targets=[('payment-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]
        for name,prefix in targets:
            client=qa.Client(urls[name],timeout=10);path=prefix+'/payments/me/data-export/orders/'
            owner={'Authorization':'Bearer '+token(),'X-Customer-Id':'2','X-Order-Access-Token':guest_capability}
            for order_id,payment_status,refund_status in ((1,'SUCCEEDED','PENDING'),(5,'PENDING',None),(6,'FAILED',None),(7,'REFUNDED','SUCCEEDED'),(8,'SUCCEEDED',None)):
                response=qa.status(client.get(path+str(order_id)+'?customerId=2',headers=owner),200,'owned stored payment')
                document=response.document()
                assert set(document)=={'schemaVersion','scope','complete','generatedAt','payment','refund','omissions'}
                assert document['schemaVersion']==1 and document['scope']=='payment-for-order' and document['complete'] is False
                payment=document['payment'];assert set(payment)==payment_fields
                assert payment['orderId']==order_id and payment['status']==payment_status and payment['amount']==123.45 and payment['currency']=='PLN'
                assert payment['collectedAt']==('2025-01-02T03:04:05Z' if order_id==8 else None)
                refund=document['refund']
                if refund_status is None:assert refund is None
                else:
                    assert set(refund)==refund_fields and refund['paymentId']==payment['paymentId'] and refund['orderId']==order_id
                    assert refund['amount']==123.45 and refund['currency']=='PLN' and refund['status']==refund_status
                    assert refund['submittedAt']==('2025-01-03T04:05:06Z' if order_id==7 else None)
                assert {'scope':'providerAndDomainReconciliation','status':'not-performed'} in document['omissions']
                assert {'scope':'crossServiceSnapshot','status':'not-a-snapshot'} in document['omissions']
                assert b'fixture-private' not in response.body and b'fixture_private' not in response.body and b'private-operator' not in response.body and b'private-checkout' not in response.body
                assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
                assert response.headers['content-disposition']=='attachment; filename="payment-data.json"' and response.headers['x-content-type-options']=='nosniff'
            for order_id in (2,3,4,99999,0):qa.status(client.get(path+str(order_id),headers=owner),404,'unowned/guest/missing order or missing payment')
            bad=qa.status(client.get(path+'9',headers=owner),503,'inconsistent refund order')
            assert b'"refund"' not in bad.body and b'fixture-private' not in bad.body
            other={'Authorization':'Bearer '+token(2,'other@fixture.example')}
            assert qa.status(client.get(path+'2',headers=other),200,'second owner').document()['refund']['status']=='FAILED'
            qa.status(client.get(path+'1',headers=other),404,'second owner isolated')
            qa.status(client.get(path+'3',headers={'X-Order-Access-Token':guest_capability}),401,'guest capability does not authorize account export')
            for credential,expected in ((None,401),(operator,403),(credentials['payment-service'],403),('invalid',401),(token(expired=True),401),(token(3,'disabled@fixture.example'),401)):
                headers={} if credential is None else {'Authorization':'Bearer '+credential}
                qa.status(client.get(path+'1',headers=headers),expected,'forbidden actor')
            qa.status(client.get(path+'9223372036854775808',headers=owner),400,'overflow')
            qa.status(client.get(path+'1',method='POST',headers=owner),403,'wrong method')
            qa.status(client.get(prefix+'/payments/1/refunds',headers=owner),403,'operator refund route remains private')
            checks.append(name+prefix+': stored payment/refund, all payment statuses, cash/null dates, two owners/guest, missing/inconsistent records, six denied actors and private headers')
        assert (sql('payment_db',payment_snapshot),sql('order_db',order_snapshot))==before
        checks.append('all six Payment tables and Order rows unchanged; no provider calls')
        spec=qa.Client(urls['payment-service']).get('/v3/api-docs').document()
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['payment'],'Payment')
        assert set(spec['components']['schemas']['PaymentExportData']['properties'])==payment_fields
        assert set(spec['components']['schemas']['PaymentExportRefund']['properties'])==refund_fields
        assert spec['components']['securitySchemes']['bearerAuth']['scheme']=='bearer'
        checks.append('OpenAPI contract and exact payment/refund field allowlists')
        processes['order-service'].terminate();processes['order-service'].wait(timeout=15)
        for name,prefix in targets:
            response=qa.status(qa.Client(urls[name],timeout=10).get(prefix+'/payments/me/data-export/orders/1',headers={'Authorization':'Bearer '+token()}),503,'ownership source unavailable')
            assert b'"payment"' not in response.body and b'private-checkout' not in response.body
        assert sql('payment_db',payment_snapshot)==before[0]
        checks.append('stopped Order: 503 through all three routes without local fallback')
        assert provider_calls==[]
        checks.append('local provider probe received zero requests; no Stripe credentials used')
        report={'stage':'A43.24','status':'PASS','checks':checks,'providerRequests':len(provider_calls),
                'realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(list(processes.values())):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for handle in handles:handle.close()
        if postgres:command('docker','rm','-f',postgres)
        provider.shutdown();provider.server_close();provider_thread.join(timeout=5)


if __name__=='__main__':
    main()

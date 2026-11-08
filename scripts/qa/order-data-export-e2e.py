#!/usr/bin/env python3
"""A43.21: bounded owner-only Order export against isolated PostgreSQL and JARs."""
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
    directory = ROOT / 'tmp' / ('order-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = [], [], []
    password, operator, service_token = (secrets.token_urlsafe(32) for _ in range(3))
    key = secrets.token_bytes(48)
    urls = {name: f'http://127.0.0.1:{port()}' for name in ('customer-service', 'order-service', 'api-gateway')}

    def sql(database, query):
        return command('docker', 'exec', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'fixture', '-d', database, '-Atqc', query)

    def token(customer=1, email='owner@fixture.example', expired=False):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode(
            {'typ': 'access', 'sub': str(customer), 'email': email, 'exp': int(time.time()) + (-600 if expired else 600)})
        return unsigned + '.' + base64.urlsafe_b64encode(hmac.new(key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

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
        for database in ('customer_db', 'order_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in ('customer-service', 'order-service', 'api-gateway'):
            database = 'order_db' if name == 'order-service' else 'customer_db'
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       ORDER_SERVICE_URL=urls['order-service'], PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=service_token,
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps({'order-service': service_token}),
                       CUSTOMER_JWT_SECRET=base64.b64encode(key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       ORDER_SAGA_RECOVERY_ENABLED='false', COUPON_EXPIRY_ENABLED='false',
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {name} first'
            handle = (directory / (name + '.log')).open('w'); handles.append(handle)
            process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                       env=env, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
            processes.append(process)
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
            INSERT INTO orders(customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              discount_amount,fulfillment_type,idempotency_key,guest_access_token_hash,checkout_request_hash,checkout_principal,created_at,updated_at)
            SELECT CASE WHEN n<=55 THEN 1 WHEN n=56 THEN 2 ELSE NULL END,'owner@fixture.example',123.45,'PAID',
              'Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,2.50,'HOME_DELIVERY',
              'fixture-idempotency-'||n,repeat('a',64),repeat('b',64),'private-checkout-principal',now(),now()
            FROM generate_series(1,57) n;
            INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price)
            SELECT 1,n,'SKU-'||n,'Produkt Żółć '||n,2,12.34,24.68 FROM generate_series(1,105) n;
            INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price)
            SELECT 56,1000+n,'OTHER','Other',1,10,10 FROM generate_series(1,3) n;
            INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price)
            SELECT 57,2000+n,'GUEST','Guest',1,10,10 FROM generate_series(1,2) n
            """)
        guest_capability='G'*43
        sql('order_db',"UPDATE orders SET product_id=777,quantity=3,unit_price=1.23 WHERE id=2")
        sql('order_db', "UPDATE orders SET guest_access_token_hash='"+hashlib.sha256(guest_capability.encode()).hexdigest()+"' WHERE id=57")
        snapshot="SELECT json_build_array((SELECT json_agg(o ORDER BY id) FROM orders o),(SELECT json_agg(i ORDER BY id) FROM order_items i),(SELECT json_agg(s ORDER BY id) FROM order_sagas s))::text"
        before=sql('order_db',snapshot)
        order_fields={'orderId','totalPrice','status','customerEmail','deliveryAddress','deliveryMethod','deliveryCost','promotionCode','discountAmount','fulfillmentType','deliveryDate','deliverySlot','pickupLocation','invoiceRequested','createdAt','updatedAt','legacyLine'}
        line_fields={'itemId','productId','sku','productName','quantity','unitPrice','totalPrice'}
        for name,prefix in [('order-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]:
            client=qa.Client(urls[name],timeout=10);path=prefix+'/orders/me/data-export'
            owner={'Authorization':'Bearer '+token(),'X-Customer-Id':'2','X-Order-Access-Token':guest_capability}
            cursor,collected,pages='0',[],0
            while True:
                response=qa.status(client.get(path+f'?size=20&cursor={cursor}&customerId=2',headers=owner),200,'owned headers')
                document=response.document();pages+=1
                assert set(document)=={'schemaVersion','scope','complete','generatedAt','orders','hasMore','nextCursor','omissions'}
                assert document['scope']=='orders-page' and document['complete'] is False and len(document['orders'])<=20
                assert all(set(row)==order_fields for row in document['orders'])
                assert all(row['totalPrice']==123.45 and row['deliveryAddress']['lastName']=='Żółć' for row in document['orders'])
                for row in document['orders']:
                    assert row['legacyLine']==({'productId':777,'quantity':3,'unitPrice':1.23} if row['orderId']==2 else None)
                assert 'fixture-idempotency-' not in response.body.decode() and 'private-checkout-principal' not in response.body.decode()
                assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
                assert response.headers['content-disposition']=='attachment; filename="orders-data-page.json"'
                assert response.headers['x-content-type-options']=='nosniff'
                collected.extend(row['orderId'] for row in document['orders'])
                if not document['hasMore']:assert document['nextCursor']=='0';break
                cursor=document['nextCursor'];assert cursor.isdigit() and pages<10
            assert pages==3 and collected==list(range(55,0,-1))
            cursor,collected,pages='0',[],0
            while True:
                response=qa.status(client.get(path+f'/1/items?size=50&cursor={cursor}',headers=owner),200,'owned items')
                document=response.document();pages+=1
                assert set(document)=={'schemaVersion','scope','complete','generatedAt','orderId','items','hasMore','nextCursor','omissions'}
                assert document['scope']=='order-items-page' and document['complete'] is False and document['orderId']==1
                assert len(document['items'])<=50 and all(set(row)==line_fields for row in document['items'])
                assert all(row['quantity']==2 and row['unitPrice']==12.34 and row['totalPrice']==24.68 for row in document['items'])
                assert response.headers['content-disposition']=='attachment; filename="order-items-data-page.json"'
                assert 'no-store' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
                collected.extend(row['itemId'] for row in document['items'])
                if not document['hasMore']:assert document['nextCursor']=='0';break
                cursor=document['nextCursor'];assert cursor.isdigit() and pages<10
            assert pages==3 and collected==list(range(105,0,-1))
            for order_id in (56,57,99999):qa.status(client.get(path+f'/{order_id}/items',headers=owner),404,'unowned or missing items')
            qa.status(client.get(path+'/57/items',headers={'X-Order-Access-Token':guest_capability}),401,'guest capability does not authorize account export')
            empty=qa.status(client.get(path+'/55/items',headers=owner),200,'owned empty order').document()
            assert empty['items']==[] and empty['hasMore'] is False
            empty=qa.status(client.get(path+'/1/items?cursor=1',headers=owner),200,'exhausted owned page').document()
            assert empty['items']==[] and empty['hasMore'] is False
            other={'Authorization':'Bearer '+token(2,'other@fixture.example')}
            assert [row['orderId'] for row in qa.status(client.get(path,headers=other),200,'second owner headers').document()['orders']]==[56]
            assert len(qa.status(client.get(path+'/56/items',headers=other),200,'second owner items').document()['items'])==3
            qa.status(client.get(path+'/1/items',headers=other),404,'second owner cannot export first owner items')
            for route in (path,path+'/1/items'):
                for credential,expected in ((None,401),(operator,403),(service_token,403),('invalid',401),(token(expired=True),401),(token(3,'disabled@fixture.example'),401)):
                    headers={} if credential is None else {'Authorization':'Bearer '+credential}
                    qa.status(client.get(route,headers=headers),expected,'forbidden actor')
                for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):
                    qa.status(client.get(route+query,headers=owner),400,'invalid window')
                qa.status(client.get(route,method='POST',headers=owner),403,'wrong method')
            checks.append(name+prefix+': 55 orders and 105 lines, bounded pages, two owners/guest, six denied actors and invalid windows')
        assert sql('order_db',snapshot)==before;checks.append('order/item/saga fixture rows unchanged')
        spec=qa.Client(urls['order-service']).get('/v3/api-docs').document()
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['order'],'Order')
        assert set(spec['components']['schemas']['OrderExportData']['properties'])==order_fields
        assert set(spec['components']['schemas']['OrderExportLine']['properties'])==line_fields
        assert spec['components']['securitySchemes']['bearerAuth']['scheme']=='bearer'
        checks.append('OpenAPI contract and explicit header/item allowlists')
        report={'stage':'A43.21','status':'PASS','checks':checks,'realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)
        for handle in handles:
            handle.close()
        if postgres:
            command('docker', 'rm', '-f', postgres)


if __name__ == '__main__':
    main()

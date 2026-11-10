#!/usr/bin/env python3
"""A43.22: owner-only shipment export with isolated Customer/Order/Delivery/Gateway."""
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
    deadline = time.time() + 60
    while True:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=60)
        if result.returncode == 0:
            return result.stdout.strip()
        if len(args) > 3 and args[3] == 'createdb' and time.time() < deadline:
            time.sleep(0.5)
            continue
        if result.stderr:
            print(result.stderr, flush=True)
        raise RuntimeError(f'Fixture command failed: {args[0]} {args[1] if len(args)>1 else ""}')


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def main():
    directory = ROOT / 'tmp' / ('shipment-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = {}, [], []
    password, operator = (secrets.token_urlsafe(32) for _ in range(2))
    credentials = {name: secrets.token_urlsafe(32) for name in ('order-service', 'delivery-service')}
    key = secrets.token_bytes(48)
    names = ('customer-service', 'order-service', 'delivery-service', 'api-gateway')
    urls = {name: f'http://127.0.0.1:{port()}' for name in names}

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
        for database in ('customer_db', 'order_db', 'delivery_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in names:
            database = {'order-service': 'order_db', 'delivery-service': 'delivery_db'}.get(name, 'customer_db')
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       ORDER_SERVICE_URL=urls['order-service'], PLATFORM_ORDER_URL=urls['order-service'],
                       DELIVERY_SERVICE_URL=urls['delivery-service'], PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=credentials.get(name, secrets.token_urlsafe(32)),
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
                       CUSTOMER_JWT_SECRET=base64.b64encode(key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       ORDER_SAGA_RECOVERY_ENABLED='false', COUPON_EXPIRY_ENABLED='false', DELIVERY_SYNC_ENABLED='false',
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
            INSERT INTO orders(customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              fulfillment_type,idempotency_key,guest_access_token_hash,created_at,updated_at)
            SELECT CASE WHEN n=2 THEN 2 WHEN n=3 THEN NULL ELSE 1 END,'owner@fixture.example',100,'PAID',
              'Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,'HOME_DELIVERY',
              'fixture-'||n,repeat('a',64),now(),now() FROM generate_series(1,4) n
            """)
        guest_capability = 'G' * 43
        sql('order_db', "UPDATE orders SET guest_access_token_hash='" + hashlib.sha256(guest_capability.encode()).hexdigest() + "' WHERE id=3")
        sql('delivery_db', """
            INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,created_at,updated_at,
              progress_revision,sync_state,sync_attempts,sync_error,sync_token,sync_until,sync_next_attempt_at)
            SELECT n,'TRACK-'||n,'Zażółć','STANDARD_COURIER','IN_TRANSIT',now(),now(),7,'RETRY',3,
              'fixture-private-error','fixture-private-token',now()+interval '1 hour',now() FROM generate_series(1,3) n
            """)
        shipment_snapshot = 'SELECT json_agg(s ORDER BY id)::text FROM shipments s'
        order_snapshot = 'SELECT json_agg(o ORDER BY id)::text FROM orders o'
        before = sql('delivery_db', shipment_snapshot), sql('order_db', order_snapshot)
        fields = {'shipmentId', 'orderId', 'trackingNumber', 'carrier', 'methodCode', 'status', 'createdAt', 'updatedAt'}
        targets = [('delivery-service', ''), ('api-gateway', '/api'), ('api-gateway', '/api/v1')]
        for name, prefix in targets:
            client = qa.Client(urls[name], timeout=10); path = prefix + '/deliveries/me/data-export/orders/'
            owner = {'Authorization': 'Bearer ' + token(), 'X-Customer-Id': '2', 'X-Order-Access-Token': guest_capability}
            response = qa.status(client.get(path + '1?customerId=2', headers=owner), 200, 'owned shipment')
            document = response.document()
            assert set(document) == {'schemaVersion', 'scope', 'complete', 'generatedAt', 'shipment', 'omissions'}
            assert document['schemaVersion'] == 1 and document['scope'] == 'shipment-for-order' and document['complete'] is False
            row = document['shipment']; assert set(row) == fields
            assert row['orderId'] == 1 and row['trackingNumber'] == 'TRACK-1' and row['carrier'] == 'Zażółć' and row['status'] == 'IN_TRANSIT'
            assert {'scope': 'crossServiceSnapshot', 'status': 'not-a-snapshot'} in document['omissions']
            assert b'fixture-private' not in response.body
            assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
            assert response.headers['content-disposition'] == 'attachment; filename="shipment-data.json"'
            assert response.headers['x-content-type-options'] == 'nosniff'
            for order_id in (2, 3, 4, 99999, 0):
                qa.status(client.get(path + str(order_id), headers=owner), 404, 'other/guest/missing order or missing shipment')
            other = {'Authorization': 'Bearer ' + token(2, 'other@fixture.example')}
            assert qa.status(client.get(path + '2', headers=other), 200, 'second owner').document()['shipment']['orderId'] == 2
            qa.status(client.get(path + '1', headers=other), 404, 'second owner isolated')
            qa.status(client.get(path + '3', headers={'X-Order-Access-Token': guest_capability}), 401, 'guest capability denied')
            for credential, expected in ((None, 401), (operator, 403), (credentials['delivery-service'], 403),
                                         ('invalid', 401), (token(expired=True), 401), (token(3, 'disabled@fixture.example'), 401)):
                headers = {} if credential is None else {'Authorization': 'Bearer ' + credential}
                qa.status(client.get(path + '1', headers=headers), expected, 'forbidden actor')
            qa.status(client.get(path + '9223372036854775808', headers=owner), 400, 'overflow')
            qa.status(client.get(path + '1', method='POST', headers=owner), 403, 'wrong method')
            checks.append(name + prefix + ': owner, other and guest isolation, missing shipment, six denied actors, headers and explicit fields')
        assert (sql('delivery_db', shipment_snapshot), sql('order_db', order_snapshot)) == before
        checks.append('shipment synchronization and order rows unchanged')
        spec = qa.Client(urls['delivery-service']).get('/v3/api-docs').document()
        contract = json.loads((ROOT / 'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec, contract['services']['delivery'], 'Delivery')
        assert set(spec['components']['schemas']['ShipmentExportData']['properties']) == fields
        assert spec['components']['securitySchemes']['bearerAuth']['scheme'] == 'bearer'
        checks.append('OpenAPI contract and exact shipment field allowlist')
        # Terminate only our disposable Order fixture; local shipment data must never be a fallback.
        processes['order-service'].terminate(); processes['order-service'].wait(timeout=15)
        for name, prefix in targets:
            response = qa.status(qa.Client(urls[name], timeout=10).get(prefix + '/deliveries/me/data-export/orders/1',
                                 headers={'Authorization': 'Bearer ' + token()}), 503, 'authority unavailable')
            assert b'TRACK-1' not in response.body and b'fixture-private' not in response.body
        assert sql('delivery_db', shipment_snapshot) == before[0]
        checks.append('Order unavailable: all three routes fail closed with 503 and no shipment read/write fallback')
        report = {'stage': 'A43.22', 'status': 'PASS', 'checks': checks, 'realUserDataAccessed': False,
                  'fullAccountExportComplete': False, 'deployment': False}
        (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'checks': len(checks), 'report': str(directory / 'report.json')}))
    finally:
        for process in reversed(list(processes.values())):
            if process.poll() is None:
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

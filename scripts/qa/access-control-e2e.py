#!/usr/bin/env python3
"""A01/A02/A04/A05/A12 regression using isolated databases, broker and real packaged services.

Requires Java 25, Docker, and ./mvnw package. Uses its own temporary containers,
ports, credentials and data; never attaches to the developer's databases.
"""
import argparse
import base64
import concurrent.futures
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import uuid
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OFFSETS = {'api-gateway': 0, 'product-service': 1, 'order-service': 2, 'inventory-service': 3,
           'payment-service': 4, 'notification-service': 6, 'customer-service': 7,
           'delivery-service': 8, 'promotion-service': 9, 'wishlist-service': 10,
           'review-service': 11, 'recommendation-service': 12, 'chat-service': 13,
           'returns-service': 14, 'billing-service': 15}
CHECKS = 0


def request(url, method='GET', body=None, token=None, headers=None, expected=200):
    global CHECKS
    outgoing = dict(headers or {})
    if token: outgoing['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body).encode()
    if data is not None: outgoing['Content-Type'] = 'application/json'
    req = urllib.request.Request(url, data=data, method=method, headers=outgoing)
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            status, content = response.status, response.read()
            if method == 'OPTIONS' and outgoing.get('Access-Control-Request-Method') == 'DELETE':
                assert response.headers.get('Access-Control-Allow-Origin') == outgoing['Origin']
                assert 'DELETE' in response.headers.get('Access-Control-Allow-Methods', '')
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
    assert status == expected, f'{method} {url}: expected {expected}, got {status}; {content[:250]!r}'
    CHECKS += 1
    if content.startswith((b'%PDF', b'PK')): return content
    return json.loads(content) if content else None


def command(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def await_payment_follow_ups(api, order_id, operator):
    deadline = time.monotonic()+30
    while time.monotonic() < deadline:
        tasks = request(api+f'/payments/follow-ups?orderId={order_id}', token=operator)
        assert tasks and all(not {'payload', 'recipient', 'billingData', 'address'}.intersection(row) for row in tasks)
        assert all(row['status'] != 'DEAD_LETTER' for row in tasks), tasks
        if all(row['status'] == 'SUCCEEDED' for row in tasks): return
        time.sleep(.1)
    raise AssertionError('Payment follow-ups did not converge: '+repr(tasks))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port-base', type=int, default=28180)
    parser.add_argument('--pagination', action='store_true', help='Also exercise A29 with 100,000 rows per business list')
    parser.add_argument('--quality-gates',action='store_true',help='A33 schema/security/load/isolated chaos gates')
    parser.add_argument('--chaos-service',default='payment-service')
    args = parser.parse_args()
    logdir = ROOT / 'tmp' / ('access-control-e2e-' + secrets.token_hex(4))
    logdir.mkdir(parents=True)
    processes, containers, handles = [], [], []
    credentials = {service: secrets.token_urlsafe(32) for service in OFFSETS if service != 'api-gateway'}
    operator = secrets.token_urlsafe(32)
    password = secrets.token_urlsafe(24)
    ports = {service: args.port_base + offset for service, offset in OFFSETS.items()}
    urls = {service: f'http://127.0.0.1:{port}' for service, port in ports.items()}
    for port in [*ports.values(), args.port_base + 30]:
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('127.0.0.1', port))
    def docker(image, container_port, *env, command_args=()):
        container = command('docker', 'run', '-d', '--rm', '-p', f'127.0.0.1::{container_port}', *env, image, *command_args)
        containers.append(container)
        port = command('docker', 'port', container, str(container_port)).rsplit(':', 1)[1]
        return container, port
    try:
        postgres, db_port = docker('postgres:16-alpine', 5432, '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD=' + password,
                                  command_args=('-c', 'shared_preload_libraries=pg_stat_statements') if args.pagination else ())
        redis, redis_port = docker('redis:7-alpine', 6379)
        mail, mail_port = docker('axllent/mailpit:latest', 1025)
        for _ in range(60):
            ready = subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-h', '127.0.0.1', '-U', 'audit'], capture_output=True).returncode == 0
            if ready: break
            time.sleep(1)
        else: raise RuntimeError('Isolated PostgreSQL failed to start')
        for service in credentials:
            command('docker', 'exec', postgres, 'createdb', '-U', 'audit', service.replace('-service', '') + '_db')
        for service in OFFSETS:
            jars = list((ROOT / service / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Build {service} first: ./mvnw package'
            env = {key: value for key, value in os.environ.items() if not key.startswith(('PLATFORM_', 'CUSTOMER_JWT_', 'CUSTOMER_MFA_', 'QA_OPERATOR_'))}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=str(ports[service]),
                       REDIS_URL=f'redis://127.0.0.1:{redis_port}', GATEWAY_RATE_LIMIT_ENABLED='false',
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{service.replace("-service", "")}_db',
                       DB_USERNAME='audit', DB_PASSWORD=password, MAIL_HOST='127.0.0.1', MAIL_PORT=mail_port,
                       PLATFORM_AUTH_URL=urls['customer-service'], PLATFORM_ORDER_URL=urls['order-service'],
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            for downstream in credentials:
                env[downstream.upper().replace('-', '_') + '_URL'] = urls[downstream]
            if service != 'api-gateway': env['PLATFORM_SERVICE_TOKEN'] = credentials[service]
            if service == 'order-service' and args.pagination:
                # Keep unrelated background reads inside the same bounded SQL
                # measurement window as the 50-row pagination fixture.
                env['ORDER_SAGA_RECONCILIATION_BATCH_SIZE'] = '50'
            if service == 'customer-service':
                env.update(PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials), PLATFORM_OPERATOR_TOKEN=operator,
                           CUSTOMER_JWT_SECRET=base64.b64encode(secrets.token_bytes(48)).decode(),
                           CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode())
            log = (logdir / (service + '.log')).open('w')
            handles.append(log)
            processes.append((service, subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1',
                              '-jar', str(jars[0])], env=env, stdout=log, stderr=subprocess.STDOUT)))
            if service == 'payment-service':
                production_env, payment_jar = dict(env), jars[0]
        # An additional production instance shares only this run's disposable payment DB.
        # A client's POST must not manufacture a real payment when no provider is configured.
        production_env.update(SPRING_PROFILES_ACTIVE='prod', PAYMENT_PROVIDER='disabled', SERVER_PORT=str(args.port_base + 30))
        urls['payment-production-guard'] = f'http://127.0.0.1:{args.port_base + 30}'
        log = (logdir / 'payment-production-guard.log').open('w')
        handles.append(log)
        processes.append(('payment-production-guard', subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1',
                          '-jar', str(payment_jar)], env=production_env, stdout=log, stderr=subprocess.STDOUT)))
        def ready(item):
            service, process = item
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if process.poll() is not None: raise RuntimeError(f'{service} exited; see {logdir / (service + ".log")}')
                try:
                    with urllib.request.urlopen(urls[service] + '/actuator/health', timeout=2) as response:
                        if response.status == 200: return service
                except (OSError, urllib.error.URLError): pass
                time.sleep(1)
            raise RuntimeError(f'{service} health timeout; see {logdir}')
        with concurrent.futures.ThreadPoolExecutor(max_workers=15) as pool:
            for service in pool.map(ready, processes): print('READY', service, flush=True)
        api = urls['api-gateway'] + '/api/v1'
        # Real registration creates password hashes and uses authenticated notification delivery.
        customers = []
        for name in ['one', 'two']:
            email = name + '@audit.example'
            body = dict(email=email, password='AuditPass-123!', firstName=name, lastName='Tester',
                        street='Testowa 1', postalCode='00-001', city='Warszawa', country='Polska')
            request(api + '/customers/register', 'POST', body, expected=201)
            # Fixture setup only, inside this run's isolated database.
            command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'customer_db', '-c',
                    f"UPDATE customers SET enabled=true WHERE email='{email}'")
            session = request(api + '/customers/login', 'POST', dict(email=email, password=body['password']))
            customers.append((session['customer']['id'], email, session['accessToken']))
        one, two = customers
        # A28 public batch has the same fields as the existing single-item read.
        for base in [api + '/inventory', urls['inventory-service'] + '/inventory']:
            batch = request(base + '/items/batch?productIds=2,1,2,9223372036854775807')
            assert [item['productId'] for item in batch] == [2, 1]
            assert batch[0] == request(base + '/items/2')
            assert batch[1] == request(base + '/items/1')
            for query in ['0', '-1', 'bad', ','.join(['1'] * 101)]:
                request(base + '/items/batch?productIds=' + query, expected=400)
            request(base + '/items/batch', 'POST', {}, expected=401)
        request(api + '/customers/operator-session', token=operator)
        request(api + '/customers/operator-session', token=one[2], expected=403)
        request(api + '/customers', token=operator)
        request(api + f'/customers/{one[0]}', token=two[2], expected=403)
        # The same perimeter holds through the gateway and every direct service port.
        private = {'order-service': '/orders', 'inventory-service': '/inventory/reservations/1',
                   'payment-service': '/payments', 'notification-service': '/notifications',
                   'promotion-service': '/promotions/codes', 'chat-service': '/chat/messages',
                   'returns-service': '/returns', 'billing-service': '/invoices', 'review-service': '/reviews/moderation/pending'}
        for service, path in private.items():
            for prefix in [urls[service], api]:
                request(prefix + path, expected=401)
                request(prefix + path, token=one[2], expected=403)
                request(prefix + path, headers={'X-Role': 'OPERATOR', 'X-Customer-Id': str(one[0])}, expected=401)
        for resource in ['orders', 'wishlists', 'invoices', 'reviews', 'returns', 'recommendations']:
            request(api + f'/{resource}/customer/{one[0]}', token=one[2])
            request(api + f'/{resource}/customer/{one[0]}', token=two[2], expected=403)
        products = request(api + '/products')
        product = next(product for product in products if product['active'] and float(product['price']) <= 1000)
        product_id = product['id']
        stock = request(api + f'/inventory/items/{product_id}')
        request(api + f'/inventory/items/{product_id}', 'PATCH', {'availableQuantity': 100, 'expectedVersion': stock['version'], 'operationId': str(uuid.uuid4())}, token=operator)
        request(api + '/invoices', 'POST', {}, token=credentials['recommendation-service'], expected=403)
        request(api + '/chat/messages/1/reply', 'POST', {}, token=credentials['payment-service'], expected=403)
        address = dict(firstName='Audit', lastName='Tester', street='Testowa 1', postalCode='00-001', city='Warszawa', country='Polska')
        base_order = dict(items=[dict(productId=product_id, quantity=1)], customerEmail=one[1], deliveryAddress=address,
                          deliveryMethod='STANDARD_COURIER', invoiceRequested=True)
        expensive = next(product for product in products if product['active'] and float(product['price']) > 1000)
        large_order = dict(base_order, items=[dict(productId=expensive['id'], quantity=1)], customerId=None, deliveryCost=0)
        guest_access = {'X-Order-Access-Token': secrets.token_urlsafe(32)}
        before_orders = request(api + '/orders', token=operator)
        denied = request(api + '/orders', 'POST', large_order, headers=guest_access, expected=403)
        assert denied['details']['code'] == 'ACCOUNT_REQUIRED'
        request(api + '/orders', 'POST', large_order, token=one[2], headers=guest_access, expected=403)
        assert len(request(api + '/orders', token=operator)) == len(before_orders)
        stock = request(api + f'/inventory/items/{expensive["id"]}')
        request(api + f'/inventory/items/{expensive["id"]}', 'PATCH', {'availableQuantity': 100, 'expectedVersion': stock['version'], 'operationId': str(uuid.uuid4())}, token=operator)
        request(api + '/orders', 'POST', dict(large_order, customerId=one[0]), token=one[2], expected=201)
        points = request(api + '/deliveries/pickup-locations')
        assert points and points[0]['code'] == 'WARSAW_STORE'
        point = points[0]
        pickup_payload = dict(base_order, deliveryMethod='STORE_PICKUP', fulfillmentType='STORE_PICKUP',
                              pickupLocation=point['code'], customerId=one[0], deliveryCost=999)
        pickup = request(api + '/orders', 'POST', pickup_payload, token=one[2], expected=201)
        assert pickup['pickupLocation'] == point['name'] and pickup['deliveryCost'] == 0
        request(api + '/orders', 'POST', dict(pickup_payload, pickupLocation='CLIENT_INVENTED_STORE'), token=one[2], expected=400)
        quote = dict(methodCode='STANDARD_COURIER', postalCode='00-001', itemCount=1, subtotal=100, pickupLocation=point['code'])
        request(api + '/deliveries/quote', 'POST', quote, expected=400)
        command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'delivery_db', '-c',
                "UPDATE pickup_locations SET active=false WHERE code='WARSAW_STORE'")
        assert request(api + '/deliveries/pickup-locations') == []
        request(api + '/orders', 'POST', pickup_payload, token=one[2], expected=400)
        command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'delivery_db', '-c',
                "UPDATE pickup_locations SET active=true WHERE code='WARSAW_STORE'")
        own_payload = dict(base_order, customerId=one[0])
        own = request(api + '/orders', 'POST', own_payload, token=one[2], headers={'Idempotency-Key': 'account-checkout'}, expected=201)
        own_id = own['id']
        billing = dict(company=False, buyerName='Audit Tester', street='Testowa 1', postalCode='00-001', city='Warszawa', country='Polska')
        def invoice_command(order):
            return dict(orderId=order['id'], customerId=order['customerId'], customerEmail=order['customerEmail'],
                        grossAmount=order['totalPrice'], currency='PLN', deliveryCost=order['deliveryCost'],
                        discountAmount=order['discountAmount'], items=[{key: item[key] for key in
                        ['productId', 'sku', 'productName', 'quantity', 'unitPrice', 'totalPrice']} for item in order['items']], billingData=billing)
        command_body = invoice_command(own)
        request(api + '/invoices', 'POST', command_body, token=operator, expected=409)
        request(api + '/invoices', 'POST', dict(command_body, orderId=999999999), token=operator, expected=404)
        for method in ['MADE_UP', 'CARDXYZ']:
            request(api + '/payments', 'POST', dict(orderId=own_id, paymentMethod=method, simulateFailure=False), token=one[2], expected=400)
        for method in ['CARD', 'BLIK', 'TRANSFER']:
            request(urls['payment-production-guard'] + '/payments', 'POST',
                    dict(orderId=own_id, paymentMethod=method, simulateFailure=False), token=one[2], expected=503)
        request(urls['payment-production-guard'] + '/payments', 'POST',
                dict(orderId=own_id, paymentMethod='CARD', simulateFailure=True), token=one[2], expected=503)
        request(api + f'/payments/order/{own_id}', token=one[2], expected=404)
        assert request(api + f'/orders/{own_id}', token=one[2])['status'] == 'CONFIRMED'
        request(api + f'/orders/{own_id}', token=one[2])
        request(api + f'/orders/{own_id}', token=two[2], expected=403)
        request(api + f'/orders/{own_id}', expected=401)
        request(api + '/orders', 'POST', dict(base_order, customerId=two[0], customerEmail=two[1]), token=two[2],
                headers={'Idempotency-Key': 'account-checkout'}, expected=403)
        request(api + '/orders', 'POST', dict(base_order, customerId=one[0]), token=two[2], expected=403)
        request(api + f'/orders/{own_id}/status', 'PATCH', {'status': 'PAID'}, token=one[2], expected=403)
        request(api + '/payments', 'POST', dict(orderId=own_id, paymentMethod='CARD', simulateFailure=False), token=two[2], expected=403)
        billing = dict(company=False, buyerName='Audit Tester', street='Testowa 1', postalCode='00-001', city='Warszawa', country='Polska')
        payment_payload = dict(orderId=own_id, paymentMethod='CARD', simulateFailure=False, customerId=one[0],
                               notificationRecipient=two[1], invoiceRequested=True, billingData=billing)
        paid = request(api + '/payments', 'POST', payment_payload, token=one[2], expected=201)
        assert paid['status'] == 'SUCCEEDED'
        await_payment_follow_ups(api, own_id, operator)
        invoice = request(api + f'/invoices/order/{own_id}', token=one[2])
        assert invoice['customerId'] == one[0] and invoice['customerEmail'] == one[1]
        assert request(api + '/invoices', 'POST', command_body, token=operator, expected=201)['id'] == invoice['id']
        for forged in [dict(command_body, grossAmount='0.01'), dict(command_body, currency='EUR'),
                       dict(command_body, customerId=two[0]), dict(command_body, customerEmail=two[1]),
                       dict(command_body, deliveryCost='999.00'), dict(command_body, discountAmount='999.00'),
                       dict(command_body, items=[dict(command_body['items'][0], quantity=99)])]:
            request(api + '/invoices', 'POST', forged, token=operator, expected=400)
        request(api + '/payments', 'POST', dict(orderId=own_id, paymentMethod='CARD', simulateFailure=False), token=one[2], expected=409)
        repeated_payment = request(api + '/payments', 'POST', payment_payload, token=one[2], expected=201)
        assert repeated_payment == paid
        assert request(api + f'/invoices/order/{own_id}', token=one[2])['id'] == invoice['id']
        # A second paid order has no invoice yet. Forged commands must leave it that way.
        request(api + '/payments', 'POST', dict(orderId=pickup['id'], paymentMethod='BLIK', simulateFailure=False), token=one[2], expected=201)
        pickup_invoice = invoice_command(pickup)
        request(api + '/invoices', 'POST', dict(pickup_invoice, grossAmount='0.01'), token=operator, expected=400)
        request(api + f'/invoices/order/{pickup["id"]}', token=one[2], expected=404)
        request(api + f'/orders/{pickup["id"]}/status', 'PATCH', {'status': 'SHIPPED'}, token=operator)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            issued = list(pool.map(lambda _: request(api + '/invoices', 'POST', pickup_invoice,
                          token=credentials['payment-service'], expected=201), range(4)))
        assert len({invoice['id'] for invoice in issued}) == 1
        request(api + f'/orders/{pickup["id"]}/status', 'PATCH', {'status': 'COMPLETED'}, token=operator)
        assert request(api + '/invoices', 'POST', pickup_invoice, token=operator, expected=201)['id'] == issued[0]['id']
        request(api + f'/invoices/{invoice["id"]}/pdf', token=one[2])
        request(api + f'/invoices/{invoice["id"]}/pdf', token=two[2], expected=403)
        request(api + f'/payments/{paid["id"]}', token=one[2])
        request(api + f'/payments/{paid["id"]}', token=two[2], expected=403)
        request(api + f'/payments/order/{own_id}', token=two[2], expected=403)
        correction = request(api + f'/invoices/{invoice["id"]}/corrections', 'POST',
                             {'reason': 'Audit correction', 'correctionGrossAmount': -1,
                              'idempotencyKey': str(uuid.uuid4()), 'expectedVersion': 0}, token=operator, expected=201)
        request(api + f'/invoices/corrections/{correction["id"]}/pdf', token=one[2])
        request(api + f'/invoices/corrections/{correction["id"]}/pdf', token=two[2], expected=403)
        request(api + '/invoices/export/zip', token=one[2], expected=403)
        request(api + '/invoices/export/zip', token=operator)
        return_payload = dict(orderId=own_id, customerId=one[0], customerEmail=one[1],
                              requestType='COMPLAINT', reason='Test', description='Isolated authorization test')
        request(api + '/returns', 'POST', return_payload, token=two[2], expected=403)
        request(api + '/returns', 'POST', dict(return_payload, customerId=two[0], customerEmail=two[1]), token=two[2], expected=403)
        returned = request(api + '/returns', 'POST', return_payload, token=one[2], expected=201)
        request(api + f'/returns/{returned["id"]}', token=two[2], expected=403)
        request(api + f'/returns/{returned["id"]}', token=one[2])
        request(api + f'/returns/{returned["id"]}/status', 'PATCH', {'status': 'UNDER_REVIEW'}, token=one[2], expected=403)
        request(api + f'/returns/{returned["id"]}/status', 'PATCH', dict(status='UNDER_REVIEW',note='Audited review',expectedVersion=returned['version'],operationId=str(uuid.uuid4())), token=operator)
        review = request(api + '/reviews', 'POST', dict(customerId=one[0], orderId=own_id, productId=product_id,
                         rating=5, title='Audit test', content='Verified purchase'), token=one[2], expected=201)
        request(api + f'/reviews/{review["id"]}/moderation', 'PATCH', {'status': 'PUBLISHED'}, token=one[2], expected=403)
        request(api + f'/reviews/{review["id"]}/moderation', 'PATCH', {'status': 'PUBLISHED'}, token=operator)
        public_reviews = request(api + f'/reviews/products/{product_id}')
        assert public_reviews and all(not {'customerId', 'orderId', 'moderationNote'}.intersection(row) for row in public_reviews)
        request(api + f'/deliveries/orders/{own_id}/tracking', token=two[2], expected=403)
        request(api + f'/deliveries/orders/{own_id}/tracking', token=one[2])
        request(api + f'/wishlists/customer/{one[0]}/products/{product_id}', 'PUT', token=two[2], expected=403)
        request(api + f'/wishlists/customer/{one[0]}/products/{product_id}', 'PUT', token=one[2], expected=201)
        request(api + f'/wishlists/customer/{one[0]}/products/{product_id}', 'OPTIONS', headers={
                'Origin': 'http://127.0.0.1:18070', 'Access-Control-Request-Method': 'DELETE',
                'Access-Control-Request-Headers': 'authorization'})
        request(api + f'/wishlists/customer/{one[0]}/products/{product_id}', 'DELETE', token=one[2], expected=204)
        guest_token = secrets.token_urlsafe(32)
        guest_headers = {'X-Order-Access-Token': guest_token, 'Idempotency-Key': 'guest-checkout'}
        guest = request(api + '/orders', 'POST', dict(base_order, customerId=None), headers=guest_headers, expected=201)
        guest_id = guest['id']
        request(api + f'/orders/{guest_id}', expected=401)
        request(api + f'/orders/{guest_id}', headers={'X-Order-Access-Token': secrets.token_urlsafe(32)}, expected=401)
        request(api + f'/orders/{guest_id}', headers=guest_headers)
        request(api + '/payments', 'POST', dict(orderId=guest_id, paymentMethod='CARD', simulateFailure=False, invoiceRequested=True, billingData=billing),
                headers=guest_headers, expected=201)
        await_payment_follow_ups(api, guest_id, operator)
        request(api + f'/invoices/order/{guest_id}/pdf', headers=guest_headers)
        chat_token = secrets.token_urlsafe(32)
        chat = request(api + '/chat/messages', 'POST', dict(customerName='Guest', customerEmail='guest@audit.example', message='Hello'),
                       headers={'X-Chat-Access-Token': chat_token}, expected=201)
        request(api + f'/chat/messages/{chat["id"]}', expected=401)
        request(api + f'/chat/messages/{chat["id"]}', headers={'X-Chat-Access-Token': chat_token})
        request(api + f'/chat/messages/{chat["id"]}/reply', 'POST', {'message': 'Reply'}, token=one[2], expected=403)
        request(api + f'/chat/messages/{chat["id"]}/reply', 'POST', {'message': 'Reply'}, token=operator)
        # Credential registry is internal even though the gateway forwards customer routes.
        request(urls['customer-service'] + '/internal/auth/introspect', 'POST', {'token': one[2]}, expected=401)
        request(api + f'/customers/{one[0]}/profile', 'PUT', {}, token=operator, expected=401)
        if args.pagination:
            from pagination_checks import run_pagination_checks
            def pagination_sql(database, query):
                try:
                    return command('docker', 'exec', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'audit', '-d', database, '-Atc', query)
                except subprocess.CalledProcessError as error:
                    raise RuntimeError(error.stderr) from error
            run_pagination_checks(api, urls, operator, credentials, one, two, product_id, pagination_sql, logdir)
        if args.quality_gates:
            import signal
            from quality_gates import Client,report,contract_gate,security_gate,load_gate,chaos_gate,CHAOS_PATHS
            gateway=Client(urls['api-gateway'],operator)
            gate_reports=logdir/'quality-gates'
            report('contract',lambda:contract_gate(gateway),gate_reports)
            report('security',lambda:security_gate(gateway),gate_reports)
            report('load',lambda:load_gate(gateway,Client(urls['product-service'],operator,3)),gate_reports)
            if args.chaos_service not in CHAOS_PATHS:raise ValueError('Unsupported chaos service')
            target=next(process for service,process in processes if service==args.chaos_service)
            report('chaos',lambda:chaos_gate(gateway,args.chaos_service,
                lambda:target.send_signal(signal.SIGSTOP),lambda:target.send_signal(signal.SIGCONT)),gate_reports)
            print('PASS: A33 contract/security/load/chaos gates on the disposable platform',flush=True)
        command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'customer_db', '-c',
                f'UPDATE customers SET enabled=false WHERE id={one[0]}')
        request(api + '/customers/me', token=one[2], expected=401)
        request(api + f'/orders/{own_id}', token=one[2], expected=401)
        before_invoices = request(api + '/invoices', token=operator)
        before_invoice_count = command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'billing_db', '-Atc', 'SELECT count(*) FROM invoices')
        order_process = next(process for service, process in processes if service == 'order-service')
        order_process.terminate()
        order_process.wait(timeout=15)
        request(api + '/invoices', 'POST', command_body, token=operator, expected=503)
        assert len(request(api + '/invoices', token=operator)) == len(before_invoices)
        assert command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'billing_db', '-Atc', 'SELECT count(*) FROM invoices') == before_invoice_count
        print(f'PASS: {CHECKS} HTTP checks across 15 real services plus a production payment instance, including payment guards, invoice verification, account and guest purchases, chat and role isolation.', flush=True)
    finally:
        for _, process in processes:
            if process.poll() is None:
                if os.name!='nt':
                    import signal
                    process.send_signal(signal.SIGCONT)
                process.terminate()
        for _, process in processes:
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for handle in handles: handle.close()
        for container in containers:
            subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
        print('Isolated resources removed; logs:', logdir, flush=True)


if __name__ == '__main__':
    main()

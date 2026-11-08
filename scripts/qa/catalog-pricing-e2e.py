#!/usr/bin/env python3
"""A27: real Product/Promotion/Order JARs and disposable PostgreSQL.

Counts returned database rows and dependent HTTP calls for a 1,000-product
catalog. Authentication, delivery and inventory use isolated deterministic
fixtures; the complete platform scenario is access-control-e2e.py.
"""
import concurrent.futures
from datetime import date, timedelta
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import quality_gates

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('audit_catalog_http', ROOT / 'scripts/qa/access-control-e2e.py')
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)


def main():
    directory = ROOT / 'tmp' / ('catalog-e2e-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    services = ['product-service', 'promotion-service', 'order-service']
    tokens = {service: secrets.token_urlsafe(32) for service in services}
    operator, password = secrets.token_urlsafe(32), secrets.token_urlsafe(24)
    processes, logs = [], []
    postgres = None
    local = None
    urls = {}
    product_calls, evaluations, reservations = [], [], []
    promotion_down = [False]
    history_down = [False]

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *args): pass

        def reply(self, value, status=200):
            raw = json.dumps(value).encode()
            self.send_response(status); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)

        def forward(self, service, method, body=None):
            try:
                value = http.request(urls[service] + self.path, method, body,
                                     headers={'Authorization': self.headers.get('Authorization', '')})
                self.reply(value)
            except Exception:
                self.reply({'error': 'Isolated forwarding fixture failed'}, 502)

        def do_GET(self):
            if self.path.startswith('/internal/products/price-history/'):
                if history_down[0]: self.reply({'error': 'Isolated history outage'}, 503)
                else: self.forward('product-service', 'GET')
            elif self.path.startswith('/internal/products/batch?'):
                product_calls.append(urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)['ids'][0])
                self.forward('product-service', 'GET')
            else: self.reply({'error': 'Unexpected individual product request'}, 500)

        def do_POST(self):
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                chunks = []
                while True:
                    size = int(self.rfile.readline().split(b';', 1)[0], 16)
                    if not size: self.rfile.readline(); break
                    chunks.append(self.rfile.read(size)); self.rfile.read(2)
                raw = b''.join(chunks)
            else: raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            body = json.loads(raw)
            if self.path == '/internal/auth/introspect':
                if self.headers.get('X-Service-Token') not in tokens.values(): self.reply({}, 401); return
                token = body.get('token')
                actor = {'role': 'ANONYMOUS', 'service': None, 'customerId': None, 'email': None}
                if token == operator: actor['role'] = 'OPERATOR'
                for service, expected in tokens.items():
                    if token == expected: actor.update(role='SERVICE', service=service)
                self.reply(actor)
            elif self.path == '/promotions/evaluate':
                evaluations.append(body)
                if promotion_down[0]: self.reply({'error': 'Isolated promotion outage'}, 503)
                else: self.forward('promotion-service', 'POST', body)
            elif self.path == '/deliveries/quote':
                self.reply(dict(methodCode='STANDARD_COURIER', deliveryCost=0, fulfillmentType='HOME_DELIVERY',
                                selectedDate=body['requestedDate']))
            elif self.path == '/inventory/reservations':
                reservations.append(body)
                self.reply(dict(reservationId=100000 + body['orderId'], orderId=body['orderId'], status='RESERVED', expiresAt=None))
            else: self.reply({'error': 'Unexpected fixture operation'}, 500)

    def sql(database, query):
        return http.command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', database, '-Atc', query)

    def reset_stats(): sql('product_db', 'SELECT pg_stat_statements_reset()')

    def product_stats():
        return tuple(map(int, sql('product_db', "SELECT coalesce(sum(calls),0),coalesce(sum(rows),0) "
                     "FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname='product_db') "
                     "AND (lower(query) LIKE 'select%from products%' OR lower(query) LIKE 'select%from catalog_layout%')").split('|')))

    try:
        local = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        local.daemon_threads = True
        threading.Thread(target=local.serve_forever, daemon=True).start()
        fixture_url = f'http://127.0.0.1:{local.server_port}'
        postgres = http.command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432',
                                '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD=' + password,
                                'postgres:16-alpine', '-c', 'shared_preload_libraries=pg_stat_statements')
        db_port = http.command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            # The image starts a temporary Unix-socket-only server during init.
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-h', '127.0.0.1', '-U', 'audit'], capture_output=True).returncode == 0: break
            time.sleep(1)
        else: raise RuntimeError('Disposable PostgreSQL did not start')
        for service in services:
            database = service.replace('-service', '') + '_db'
            http.command('docker', 'exec', postgres, 'createdb', '-U', 'audit', database)
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
            urls[service] = f'http://127.0.0.1:{port}'
            env = {key: value for key, value in os.environ.items() if not key.startswith(('PLATFORM_', 'CUSTOMER_', 'QA_OPERATOR_'))}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=str(port), PLATFORM_AUTH_URL=fixture_url,
                       PLATFORM_SERVICE_TOKEN=tokens[service], DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}',
                       DB_USERNAME='audit', DB_PASSWORD=password, PRODUCT_SERVICE_URL=fixture_url,
                       PROMOTION_SERVICE_URL=fixture_url, INVENTORY_SERVICE_URL=fixture_url, DELIVERY_SERVICE_URL=fixture_url,
                       RESILIENCE_RETRY_MAX_ATTEMPTS='1', ORDER_SAGA_RECOVERY_FIXED_DELAY_MS='600000',
                       CATALOG_LAYOUT_TTL_MS='30000', CATALOG_LAYOUT_MAX_PRODUCTS='10000',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / service / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {service} first'
            log = (directory / (service + '.log')).open('w'); logs.append(log)
            processes.append(subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                              env=env, stdout=log, stderr=subprocess.STDOUT))
            if service == 'product-service': product_environment, product_jar = dict(env), jars[0]
        ready_targets = list(zip(services, processes))
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0)); replica_port = probe.getsockname()[1]
        urls['product-replica'] = f'http://127.0.0.1:{replica_port}'
        log = (directory / 'product-replica.log').open('w'); logs.append(log)
        processes.append(subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(product_jar)],
                                          env=dict(product_environment, SERVER_PORT=str(replica_port)), stdout=log, stderr=subprocess.STDOUT))
        ready_targets.append(('product-replica', processes[-1]))
        def ready(item):
            service, process = item
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                if process.poll() is not None: raise RuntimeError(f'{service} exited; see {directory}')
                try:
                    with urllib.request.urlopen(urls[service] + '/actuator/health', timeout=2) as response:
                        if response.status == 200: return
                except OSError: pass
                time.sleep(.5)
            raise RuntimeError(f'{service} readiness timeout; see {directory}')
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(ready, ready_targets))
        contract = json.loads((ROOT / 'scripts/qa/contracts/api-gateway-v1.json').read_text())
        quality_gates.check_document(http.request(urls['promotion-service'] + '/v3/api-docs'),
                                    contract['services']['promotion'], 'Promotion')
        sql('product_db', 'CREATE EXTENSION pg_stat_statements')
        sql('product_db', "INSERT INTO products(sku,name,category,manufacturer,model,technical_parameters,price,active,image_url,created_at,updated_at) "
            "SELECT 'A27-'||n,'Fixture '||n,'TEST','Brand','Model','{}'::jsonb,1.00,true,'/unchanged.png',now(),now() "
            "FROM generate_series(1,1000) n")
        product = urls['product-service']
        catalog = http.request(product + '/products')
        selected = [item for item in catalog if item['sku'].startswith('A27-')]
        by_id = {item['id']: item for item in catalog}
        positions = {item['id']: index for index, item in enumerate(catalog)}
        assert len(selected) == 1000
        print(f'PASS catalog fixture: {len(catalog)} total products', flush=True)

        endpoint = product + '/internal/products/batch?ids=' + str(selected[0]['id'])
        http.request(endpoint, expected=401)
        for token in [operator, tokens['product-service'], tokens['promotion-service']]: http.request(endpoint, token=token, expected=403)
        http.request(product + '/internal/products/batch?ids=0', token=tokens['order-service'], expected=400)
        http.request(product + '/internal/products/batch?ids=' + ','.join(['1'] * 101), token=tokens['order-service'], expected=400)

        chosen = selected[725]
        http.request(product + '/products/' + str(chosen['id']))  # Cold, bounded ID-index population.
        http.request(urls['product-replica'] + '/products/' + str(chosen['id']))
        reset_stats(); start = len(evaluations)
        assert http.request(product + '/products/' + str(chosen['id'])) == chosen
        assert product_stats() == (2, 2), product_stats()
        assert len(evaluations) == start + 1 and len(evaluations[-1]) == 1
        assert evaluations[-1][0]['catalogIndex'] == positions[chosen['id']]
        assert evaluations[-1][0]['catalogSize'] == len(catalog)
        print('PASS single product: 2 SQL queries / 2 returned rows / 1 evaluated product', flush=True)

        for size in [1, 5, 50, 100]:
            cart = selected[:size]
            before_batches, before_evaluations = len(product_calls), len(evaluations)
            reset_stats()
            headers = {'X-Order-Access-Token': secrets.token_urlsafe(32), 'Idempotency-Key': 'catalog-' + str(size)}
            body = dict(customerEmail='catalog@audit.example', deliveryMethod='STANDARD_COURIER',
                        deliveryAddress=dict(firstName='Jan', lastName='Test', street='Testowa 1', postalCode='00-001', city='City', country='PL'),
                        deliveryDate=str(date.today() + timedelta(days=14)), items=[dict(productId=item['id'], quantity=1) for item in cart])
            order = http.request(urls['order-service'] + '/orders', 'POST', body, headers=headers, expected=201)
            assert len(order['items']) == size
            assert Decimal(str(order['totalPrice'])) == sum(Decimal(str(item['price'])) for item in cart)
            assert len(product_calls) == before_batches + 1 and len(evaluations) == before_evaluations + 1
            assert len(evaluations[-1]) == size
            assert product_stats() == (2, size + 1), product_stats()
            for item in order['items']: assert Decimal(str(item['unitPrice'])) == Decimal(str(by_id[item['productId']]['price']))
            print(f'PASS checkout {size} products: 1 product call / 1 promotion call / 2 SQL / {size + 1} rows', flush=True)

        before_reservations = len(reservations)
        missing = dict(body, items=[dict(productId=selected[0]['id'], quantity=1), dict(productId=9223372036854775807, quantity=1)])
        http.request(urls['order-service'] + '/orders', 'POST', missing,
                     headers={'X-Order-Access-Token': secrets.token_urlsafe(32), 'Idempotency-Key': 'catalog-missing'}, expected=404)
        assert len(reservations) == before_reservations

        # Restart a Product replica: ApplicationReady records a daily snapshot
        # independently of any catalog reads, including the 1,000 inserted rows.
        processes[-1].terminate(); processes[-1].wait(timeout=15)
        log = (directory / 'product-replica-restart.log').open('w'); logs.append(log)
        replica = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(product_jar)],
                                   env=dict(product_environment, SERVER_PORT=str(replica_port)), stdout=log, stderr=subprocess.STDOUT)
        processes.append(replica); ready(('product-replica', replica))
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            daily = int(sql('product_db', "SELECT count(DISTINCT product_id) FROM product_price_history_events WHERE source_type='DAILY' AND rotation_date=(now() AT TIME ZONE 'UTC')::date"))
            if daily == len(catalog): break
            time.sleep(.1)
        else: raise AssertionError('Daily snapshot did not capture products without reads')
        history_endpoint = product + '/internal/products/price-history/' + str(chosen['id'])
        http.request(history_endpoint, expected=401)
        for token in [operator, tokens['order-service'], tokens['product-service']]:
            http.request(history_endpoint, token=token, expected=403)
        http.request(history_endpoint, token=tokens['promotion-service'])
        before_history = sql('product_db', 'SELECT count(*) FROM product_price_history_events')
        for _ in range(3): http.request(product + '/products/' + str(chosen['id']))
        assert sql('product_db', 'SELECT count(*) FROM product_price_history_events') == before_history

        # Catalog membership invalidation reaches a different Product JVM before TTL.
        new_product = http.request(product + '/products', 'POST', dict(sku='A27-NEW', name='New fixture', category='TEST',
                                   manufacturer='Brand', model='Model', price=1.00, active=True), token=operator, expected=201)
        updated_catalog = http.request(product + '/products')
        for target in [product, urls['product-replica']]:
            actual = http.request(target + '/products/' + str(chosen['id']))
            assert actual == next(item for item in updated_catalog if item['id'] == chosen['id'])
            assert evaluations[-1][0]['catalogSize'] == len(catalog) + 1
        changed_offers = sum(Decimal(str(item['price'])) != Decimal(str(by_id[item['id']]['price']))
                             for item in updated_catalog if item['id'] in by_id)
        assert int(sql('product_db', "SELECT count(*) FROM product_price_history_events WHERE source_type='CATALOG_LAYOUT_CHANGE'")) == changed_offers
        history_url = urls['promotion-service'] + '/promotions/history/' + str(new_product['id'])
        low = http.request(product + '/products/' + str(new_product['id']), 'PATCH', {'price': .40}, token=operator)
        http.request(product + '/products/' + str(new_product['id']), 'PATCH', {'price': 1.00}, token=operator)
        changes = [row for row in http.request(history_url) if row['sourceType'] in ('CREATE', 'PRICE_CHANGE')]
        assert [row['priceRevision'] for row in changes] == [3, 2, 1], changes
        assert [Decimal(str(row['regularPrice'])) for row in changes] == [Decimal('1.00'), Decimal('.40'), Decimal('1.00')]
        before_noop = sql('product_db', 'SELECT count(*) FROM product_price_history_events')
        http.request(product + '/products/' + str(new_product['id']), 'PATCH', {'name': 'Renamed fixture'}, token=operator)
        assert sql('product_db', 'SELECT count(*) FROM product_price_history_events') == before_noop
        metric = http.request(history_url + '/lowest?days=1')
        assert metric['historyComplete'] and Decimal(str(metric['lowestPrice'])) == Decimal(str(low['price']))
        sql('promotion_db', f"INSERT INTO promotion_price_history(product_id,campaign_code,regular_price,promotional_price,discount_percent,rotation_date,recorded_at) "
            f"SELECT {new_product['id']},'LEGACY_FIXTURE',1,.01,99,(now() AT TIME ZONE 'UTC')::date-n,now() FROM generate_series(0,29) n")
        incomplete = http.request(history_url + '/lowest?days=30')
        assert not incomplete['historyComplete'] and incomplete['lowestPrice'] is None and incomplete['observedDays'] == 1
        assert any(row['sourceType'] == 'LEGACY_OBSERVATION' for row in http.request(history_url))
        # Synthetic dated fixtures exercise coverage aggregation without inventing
        # any production backfill; the real scheduled current-day snapshot was checked above.
        sql('product_db', "INSERT INTO product_price_history_events(product_id,price_revision,layout_revision,source_type,campaign_code,regular_price,effective_price,discount_percent,rotation_date,catalog_index,catalog_size,recorded_at) "
            f"SELECT product_id,price_revision,layout_revision,'DAILY','DATED_FIXTURE',4,3,25,(now() AT TIME ZONE 'UTC')::date-n,catalog_index,catalog_size,now() "
            f"FROM product_price_history_events CROSS JOIN generate_series(1,29) n WHERE product_id={new_product['id']} AND source_type='CREATE'")
        complete = http.request(history_url + '/lowest?days=30')
        assert complete['historyComplete'] and complete['observedDays'] == 30
        assert Decimal(str(complete['lowestPrice'])) == Decimal(str(low['price']))
        sql('product_db', f"DELETE FROM product_price_history_events WHERE product_id={new_product['id']} AND rotation_date=(now() AT TIME ZONE 'UTC')::date-5")
        assert http.request(history_url + '/lowest?days=30')['lowestPrice'] is None
        history_down[0] = True
        unavailable = http.request(history_url + '/lowest?days=1')
        assert not unavailable['historyComplete'] and unavailable['lowestPrice'] is None
        history_down[0] = False
        print('PASS A18: no-traffic snapshots, source authorization, price/layout revisions, legacy isolation, full/gapped coverage and source outage', flush=True)
        sql('product_db', f"DELETE FROM products WHERE id={new_product['id']}")
        for target in [product, urls['product-replica']]:
            http.request(target + '/products/' + str(chosen['id']))
            assert evaluations[-1][0]['catalogSize'] == len(catalog)
        revision = sql('product_db', 'SELECT revision FROM catalog_layout')
        sql('product_db', "BEGIN; INSERT INTO products(sku,name,category,manufacturer,model,technical_parameters,price,active,created_at,updated_at) "
            "VALUES('A27-ROLLBACK','Rollback','TEST','Brand','Model','{}',1,true,now(),now()); ROLLBACK;")
        assert sql('product_db', 'SELECT revision FROM catalog_layout') == revision
        # Same-size reordering must also invalidate cached positions on both JVMs.
        old_id, moved_id = catalog[0]['id'], max(by_id) + 1000
        sql('product_db', f'UPDATE products SET id={moved_id} WHERE id={old_id}')
        for target in [product, urls['product-replica']]:
            http.request(target + '/products/' + str(chosen['id']))
            assert evaluations[-1][0]['catalogIndex'] == positions[chosen['id']] - 1
            assert evaluations[-1][0]['catalogSize'] == len(catalog)
        sql('product_db', f'UPDATE products SET id={old_id} WHERE id={moved_id}')
        for target in [product, urls['product-replica']]:
            http.request(target + '/products/' + str(chosen['id']))
            assert evaluations[-1][0]['catalogIndex'] == positions[chosen['id']]
        print('PASS version invalidation across two JVMs, create/delete and transaction rollback', flush=True)
        sql('product_db', f"UPDATE products SET active=false WHERE id={selected[0]['id']}")
        inactive = dict(body, items=[dict(productId=selected[0]['id'], quantity=1)])
        http.request(urls['order-service'] + '/orders', 'POST', inactive,
                     headers={'X-Order-Access-Token': secrets.token_urlsafe(32), 'Idempotency-Key': 'catalog-inactive'}, expected=409)
        assert len(reservations) == before_reservations

        promotion_down[0] = True
        for item in [selected[0], selected[5], selected[725]]:
            assert http.request(product + '/products/' + str(item['id']))['price'] == item['price']
        promotion_down[0] = False
        sql('product_db', f"UPDATE products SET price=2.00 WHERE id={chosen['id']}")
        current = http.request(product + '/products/' + str(chosen['id']))
        assert Decimal(str(current['regularPrice'])) == Decimal('2.00')
        assert current['imageUrl'] == chosen['imageUrl']
        timings = []
        for _ in range(7):
            start = time.perf_counter(); http.request(product + '/products/' + str(chosen['id']))
            timings.append((time.perf_counter() - start) * 1000)
        print(f'PASS fresh price, lazy outage fallback, missing/inactive cart guards; observed single-read p50={statistics.median(timings):.1f}ms', flush=True)
        sql('product_db', 'TRUNCATE products')
        assert sql('product_db', 'SELECT product_count FROM catalog_layout') == '0'
        http.request(product + '/products/' + str(chosen['id']), expected=404)
        (directory / 'report.json').write_text(json.dumps({'status': 'PASS', 'httpChecks': http.CHECKS,
            'areas': ['A27 catalog budgets', 'A18 authoritative price history and coverage']}, indent=2))
        print(f'PASS {http.CHECKS} HTTP checks; logs: {directory}', flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=12)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        if local: local.shutdown(); local.server_close()
        for log in logs: log.close()
        if postgres: subprocess.run(['docker', 'rm', '-f', postgres], capture_output=True)


if __name__ == '__main__': main()

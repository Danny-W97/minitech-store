#!/usr/bin/env python3
"""A08: two JVMs sharing disposable PostgreSQL; deterministic idempotent provider fixture.

The test-only gateway is compiled outside the application JAR. No real provider,
developer database, credential, application process or port is accessed.
"""
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import http.client as http_client
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('payment_http', ROOT/'scripts/qa/access-control-e2e.py')
http = importlib.util.module_from_spec(spec); spec.loader.exec_module(http)


def main():
    logs = ROOT/'tmp'/('payment-replicas-'+secrets.token_hex(4)); logs.mkdir(parents=True)
    processes = {}; handles = []; urls = {}; postgres = None
    password = secrets.token_urlsafe(24); operator = secrets.token_urlsafe(32); service = secrets.token_urlsafe(32)
    lock = threading.Lock(); orders = {}; outcomes = {}; calls = {}; effects = {}; barriers = {}; order_reads = {}; deferred = {}
    entered = {source: threading.Event() for source in [300, 301, 302, 500]}
    unblock = {source: threading.Event() for source in entered}
    report = {}; pool = concurrent.futures.ThreadPoolExecutor(max_workers=8)

    def order(source):
        with lock:
            return dict(id=source, totalPrice=orders.get(('amount', source), 10), status=orders.get(source, 'CONFIRMED'),
                        items=[], inventoryReservationId=source, deliveryMethod='STANDARD_COURIER',
                        customerId=None, customerEmail='owner@example.test', promotionCode=None, invoiceRequested=False,lifecycleReviewRequired=False,
                        paymentState='COLLECTION_DUE' if source in deferred else None,
                        deferredPaymentMethod=deferred.get(source,{}).get('paymentMethod'),deferredPaymentId=deferred.get(source,{}).get('paymentId'))

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def body(self):
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                parts = []
                while True:
                    size = int(self.rfile.readline().split(b';')[0], 16)
                    if not size: self.rfile.readline(); break
                    parts.append(self.rfile.read(size)); self.rfile.read(2)
                raw = b''.join(parts)
            else: raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            return json.loads(raw) if raw else {}
        def reply(self, value, status=200):
            raw = json.dumps(value).encode(); self.send_response(status)
            self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(raw))); self.end_headers()
            try: self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError): pass
        def do_GET(self):
            source = int(self.path.rsplit('/', 1)[1]); snapshot = order(source)
            with lock:
                order_reads[source] = order_reads.get(source, 0)+1
                barrier = barriers.get(source) if order_reads[source] <= 2 else None
            if barrier:
                try: barrier.wait(timeout=6)
                except threading.BrokenBarrierError: pass
            self.reply(snapshot)
        def do_PATCH(self):
            body = self.body(); source = int(self.path.split('/')[2])
            if source == 301 and not entered[source].is_set():
                entered[source].set(); unblock[source].wait(20)
            with lock:
                if orders.get(source) != body['status']:
                    effects[('paid', source)] = effects.get(('paid', source), 0)+1
                orders[source] = body['status']
            self.reply(order(source))
        def do_POST(self):
            body = self.body(); path = urllib.parse.urlsplit(self.path).path
            if path == '/internal/auth/introspect':
                actor = dict(role='ANONYMOUS', service=None, customerId=None, email=None)
                if body.get('token') == operator: actor['role'] = 'OPERATOR'
                if body.get('token') == service: actor.update(role='SERVICE', service='payment-service')
                self.reply(actor); return
            if path == '/authorize':
                source = body['orderId']; key = body['key']
                with lock:
                    calls[source] = calls.get(source, 0)+1
                    identity = (source, body['amount'], body['currency'], body['method'], body['failure'])
                    previous = outcomes.get(key)
                    if previous and previous[0] != identity:
                        self.reply({}, 409); return
                    if not previous:
                        outcomes[key] = (identity, dict(approved=not body['failure'], reference='FIXTURE-'+str(source), failureReason='Declined' if body['failure'] else None))
                    outcome = outcomes[key][1]
                    attempt = calls[source]
                if source in [300, 302, 500] and attempt == 1:
                    entered[source].set(); unblock[source].wait(20)
                else: time.sleep(.15)
                self.reply(outcome); return
            if path.startswith('/internal/orders/') and path.endswith('/deferred/accept'):
                source=int(path.split('/')[3]);deferred[source]=body;self.reply(order(source));return
            if path.startswith('/inventory/'):
                source = int(path.split('/')[3]); kind = 'inventory'
            elif path.startswith('/deliveries/'):
                source = int(path.split('/')[3]); kind = 'delivery'
            else: self.reply({}, 404); return
            with lock: effects[(kind, source)] = effects.get((kind, source), 0)+1
            self.reply(dict(reservationId=source,orderId=source,status='CONFIRMED') if kind=='inventory' else {})

    fixture = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    fixture_url = 'http://127.0.0.1:'+str(fixture.server_port)

    def sql(query):
        return http.command('docker', 'exec', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'audit', '-d', 'payment_db', '-Atqc', query)
    def poll(action, predicate, seconds=8):
        until = time.monotonic()+seconds; result = None
        while time.monotonic() < until:
            result = action()
            if predicate(result): return result
            time.sleep(.05)
        raise AssertionError('Timed out; last result: '+repr(result))
    def start(name):
        with socket.socket() as probe: probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
        env = {key: value for key, value in os.environ.items() if not key.startswith(('SPRING_', 'PLATFORM_', 'DB_', 'PAYMENT_', 'OUTBOX_', 'REDIS_', 'RESILIENCE_', 'A08_'))}
        env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=str(port), PAYMENT_PROVIDER='disabled',
                   DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/payment_db?ApplicationName={name}', DB_USERNAME='audit', DB_PASSWORD=password,
                   PLATFORM_SERVICE_TOKEN=service, PLATFORM_AUTH_URL=fixture_url, PLATFORM_ORDER_URL=fixture_url, A08_FIXTURE_URL=fixture_url,
                   REDIS_URL='redis://127.0.0.1:1', MANAGEMENT_HEALTH_REDIS_ENABLED='false', OUTBOX_PUBLISHER_ENABLED='false',
                   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='2', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
                   RESILIENCE_RETRY_MAX_ATTEMPTS='1', RESILIENCE_CB_MINIMUM_NUMBER_OF_CALLS='100', RESILIENCE_CB_SLIDING_WINDOW_SIZE='100',
                   SERVER_ERROR_INCLUDE_MESSAGE='always')
        for dependency in ['ORDER', 'INVENTORY', 'DELIVERY', 'PROMOTION', 'BILLING']: env[dependency+'_SERVICE_URL'] = fixture_url
        handle = (logs/(name+'-'+secrets.token_hex(2)+'.log')).open('w'); handles.append(handle)
        processes[name] = subprocess.Popen(['java', '-Xms32m', '-Xmx160m', '-XX:TieredStopAtLevel=1', '-cp', classpath,
                                             'org.demo.qa.PaymentReplicaProbe'], env=env, stdout=handle, stderr=subprocess.STDOUT)
        urls[name] = f'http://127.0.0.1:{port}'
    def ready(name):
        until = time.monotonic()+150
        while time.monotonic() < until:
            if processes[name].poll() is not None: raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health', timeout=2) as response:
                    if response.status == 200: return
            except OSError: pass
            time.sleep(.5)
        raise RuntimeError('Readiness timed out for '+name)
    def pay(name, source, key=None, method='CARD', failure=False, headers=None):
        body = dict(orderId=source, paymentMethod=method, simulateFailure=failure, invoiceRequested=False)
        outgoing = {'Authorization': 'Bearer '+operator, 'Content-Type': 'application/json', **(headers or {})}
        if key: outgoing['Idempotency-Key'] = key
        request = urllib.request.Request(urls[name]+'/payments', data=json.dumps(body).encode(), headers=outgoing, method='POST')
        try:
            with urllib.request.urlopen(request, timeout=20) as response: return response.status, json.load(response)
        except urllib.error.HTTPError as error: return error.code, json.load(error)
        except OSError: return 0, None
    def check_once(source, expected_calls=1):
        poll(lambda: all(effects.get((kind, source)) == 1 for kind in ['paid', 'inventory', 'delivery'])
             and sql(f"SELECT count(*) FROM payment_outbox_events WHERE order_id={source} AND event_type='PAYMENT_SUCCEEDED'") == '1', bool)
        assert calls.get(source) == expected_calls, (source, calls)
        assert all(effects.get((kind, source)) == 1 for kind in ['paid', 'inventory', 'delivery']), (source, effects)
        assert sql(f'SELECT count(*) FROM payments WHERE order_id={source}') == '1'
        assert sql(f"SELECT count(*) FROM payment_outbox_events WHERE order_id={source} AND event_type='PAYMENT_SUCCEEDED'") == '1'
    def expire(source): sql(f"UPDATE payments SET processing_until=now()-interval '1 second' WHERE order_id={source}")

    try:
        jar = next((ROOT/'payment-service/target').glob('*-SNAPSHOT.jar'))
        extracted = logs/'application'; extracted.mkdir()
        with zipfile.ZipFile(jar) as archive:
            for member in archive.namelist():
                if member.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')): archive.extract(member, extracted)
        classpath = str(logs/'probe')+os.pathsep+str(extracted/'BOOT-INF/classes')+os.pathsep+str(extracted/'BOOT-INF/lib/*')
        http.command('javac', '-cp', classpath, '-d', str(logs/'probe'), str(ROOT/'scripts/qa/fixtures/PaymentReplicaProbe.java'))
        postgres = http.command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432', '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD='+password, 'postgres:16-alpine')
        db_port = http.command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-h', '127.0.0.1', '-U', 'audit'], capture_output=True).returncode == 0: break
            time.sleep(1)
        else: raise RuntimeError('Temporary PostgreSQL not ready')
        http.command('docker', 'exec', postgres, 'createdb', '-U', 'audit', 'payment_db')
        migration = (ROOT/'payment-service/src/main/resources/db/migration/V10__payment_command_result.sql').read_text(encoding='utf-8')
        # Execute the actual upgrade against legacy rows in a session-local table.
        upgraded = sql("BEGIN; CREATE TEMP TABLE payments(status varchar(20),updated_at timestamptz); "
                       "INSERT INTO payments VALUES('SUCCEEDED',now()),('FAILED',now()),('PENDING',now()); "
                       +migration+" SELECT count(*) FILTER (WHERE result_recorded_at IS NOT NULL)=2 AND "
                       "count(*) FILTER (WHERE status='PENDING' AND result_recorded_at IS NULL)=1 FROM payments; ROLLBACK;")
        assert upgraded == 't', upgraded
        start('a'); ready('a'); start('b'); ready('b')
        print('READY: two Payment JVMs, real PostgreSQL, test-only idempotent gateway', flush=True)
        print('PASS: V10 backfills terminal legacy results without finalizing unfinished PENDING rows', flush=True)

        for source in range(100, 110):
            barriers[source] = threading.Barrier(2)
            first = pool.submit(pay, 'a', source); second = pool.submit(pay, 'b', source)
            one, two = first.result(15), second.result(15)
            assert one[0] == two[0] == 201 and one[1] == two[1], (one, two)
            barriers.pop(source); check_once(source)
        report['sameOrderRaces'] = 10
        print('PASS: 10 first-insert races; identical committed responses and one set of effects', flush=True)

        barrier = threading.Barrier(2); barriers.update({200: barrier, 201: barrier})
        first = pool.submit(pay, 'a', 200, 'shared-key'); second = pool.submit(pay, 'b', 201, 'shared-key')
        pair = [first.result(15), second.result(15)]; barriers.clear()
        assert sorted(item[0] for item in pair) == [201, 409], pair
        assert calls.get(200, 0)+calls.get(201, 0) == 1
        print('PASS: cross-order key race returns 409 without a second authorization', flush=True)

        # Provider has accepted, but its response is still in flight when the owner dies.
        first = pool.submit(pay, 'a', 300, 'crash-before-record')
        assert entered[300].wait(5); processes['a'].kill(); processes['a'].wait(10); first.result(10)
        expire(300)
        assert pay('b', 300, 'replacement-key')[0] == 201
        unblock[300].set(); check_once(300, expected_calls=2)
        assert len([value for value in outcomes.values() if value[0][0] == 300]) == 1
        start('a'); ready('a')
        report['lostProviderResponse'] = 'same stored key, two calls, one authorization'
        print('PASS: crash after remote authorization/before local record; stable provider key prevents double charge', flush=True)

        # Authorization is now committed locally, but markPaid's response has not arrived.
        first = pool.submit(pay, 'a', 301)
        assert entered[301].wait(5)
        assert sql('SELECT provider_reference FROM payments WHERE order_id=301') == 'FIXTURE-301'
        processes['a'].kill(); processes['a'].wait(10); first.result(10); expire(301)
        assert pay('b', 301)[0] == 201
        unblock[301].set(); check_once(301)
        start('a'); ready('a')
        print('PASS: crash after local authorization record; recovery skips the provider', flush=True)

        # Paused old owner resumes after the successor finishes: it must not perform effects.
        first = pool.submit(pay, 'a', 302)
        assert entered[302].wait(5); processes['a'].send_signal(signal.SIGSTOP); expire(302)
        assert pay('b', 302)[0] == 201
        unblock[302].set(); processes['a'].send_signal(signal.SIGCONT)
        assert first.result(12)[0] == 409
        check_once(302, expected_calls=2)
        print('PASS: stale owner cannot overwrite result or execute order/inventory/delivery effects', flush=True)

        # Changing the command or its server-side price on an abandoned attempt fails closed.
        sql("INSERT INTO payments(order_id,idempotency_key,amount,currency,payment_method,status,created_at,updated_at) VALUES(400,'original-400',10,'PLN','CARD','PENDING',now(),now())")
        assert pay('a', 400, method='BLIK')[0] == 409
        orders[('amount', 400)] = 20
        assert pay('b', 400)[0] == 409 and calls.get(400, 0) == 0
        assert pay('a', 100, method='BLIK')[0] == 409
        assert pay('a', 100, failure=True)[0] == 409
        assert pay('b', 100, 'x'*101)[0] == 400
        print('PASS: changed method/payload/amount and oversized header rejected before side effects', flush=True)

        decline = pay('a', 410, failure=True)
        assert decline[0] == 402 and pay('b', 410, failure=True) == decline and calls[410] == 1
        deferred_response = pay('a', 411, method='CASH_ON_DELIVERY')
        assert deferred_response[0] == 201 and pay('b', 411, method='CASH_ON_DELIVERY') == deferred_response and calls.get(411, 0) == 0
        print('PASS: decline and deferred PENDING outcomes are replayed across replicas', flush=True)

        first = pool.submit(pay, 'a', 500)
        assert entered[500].wait(5)
        began = time.monotonic()
        busy = pay('b', 500, headers={'X-Request-Deadline-Ms': str(int(time.time()*1000)+250)})
        assert busy[0] == 504 and time.monotonic()-began < 2, busy
        # Same old stripe as order 500; no JVM lock can delay unrelated work.
        assert pay('a', 756)[0] == 201
        assert sql("SELECT count(*) FROM pg_stat_activity WHERE datname='payment_db' AND state='idle in transaction'") == '0'
        unblock[500].set(); assert first.result(10)[0] == 201; check_once(500)
        print('PASS: bounded contention respects deadline and holds no SQL transaction or unrelated JVM stripe', flush=True)

        # A process restart cannot alter an already committed response.
        completed = pay('a', 600); assert completed[0] == 201
        processes['a'].kill(); processes['a'].wait(10)
        assert pay('b', 600) == completed; check_once(600)
        # A client disconnects before reading any response. The other replica recovers by SQL.
        target = urllib.parse.urlsplit(urls['b'])
        connection = http_client.HTTPConnection(target.hostname, target.port, timeout=3)
        connection.request('POST', '/payments', json.dumps(dict(orderId=601, paymentMethod='CARD', simulateFailure=False, invoiceRequested=False)),
                           {'Content-Type':'application/json', 'Authorization':'Bearer '+operator})
        connection.close()
        poll(lambda: sql('SELECT status FROM payments WHERE order_id=601'), lambda value:value=='SUCCEEDED')
        assert pay('b', 601)[0] == 201; check_once(601)
        print('PASS: completed result survives owner death and a client dropping the HTTP response', flush=True)
        report.update(result='PASSED', replicas=2, scenarios=10, provider='isolated fixture, not a real payment integration')
        (logs/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print('PASS A08: replica races, key conflicts, crashes, stale owners, immutable retries and result replay', flush=True)
    finally:
        for gate in unblock.values(): gate.set()
        for process in processes.values():
            if process.poll() is None: process.send_signal(signal.SIGCONT); process.terminate()
        for process in processes.values():
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        pool.shutdown(wait=True, cancel_futures=True)
        for handle in handles: handle.close()
        if postgres: subprocess.run(['docker', 'rm', '-f', postgres], capture_output=True)
        fixture.shutdown(); fixture.server_close()
        print('Isolated resources removed; logs:', logs, flush=True)


if __name__ == '__main__': main()

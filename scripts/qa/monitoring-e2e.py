#!/usr/bin/env python3
"""A35.1: real service SQL metrics and Prometheus -> Alertmanager -> owned webhook.
All databases, network, ports, credentials and containers belong to this test.
"""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
import urllib.request
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('monitoring_check', Path(__file__).with_name('monitoring-check.py'))
check = importlib.util.module_from_spec(spec); spec.loader.exec_module(check)

FIXTURE = '''from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
metrics = b""
events = []
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        body = json.dumps(events).encode() if self.path == '/events' else metrics
        self.send_response(200)
        self.send_header('Content-Type', 'application/json' if self.path == '/events' else 'text/plain; version=0.0.4; charset=utf-8')
        self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        global metrics
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path == '/metrics': metrics = body
        elif self.path == '/webhook': events.append(json.loads(body))
        else: self.send_error(404); return
        self.send_response(200); self.end_headers()
ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
'''


def get(url):
    with urllib.request.urlopen(url, timeout=5) as response: return response.read()


def post(url, body):
    with urllib.request.urlopen(urllib.request.Request(url, data=body, method='POST'), timeout=5) as response: return response.read()


def poll(action, predicate, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = action()
        if predicate(value): return value
        time.sleep(1)
    raise AssertionError('Timed out; last state: ' + repr(value)[:500])


def main():
    directory = ROOT / 'tmp' / ('a35-monitoring-' + secrets.token_hex(4)); directory.mkdir(parents=True)
    containers = []; processes = []; handles = []; urls = {}
    network = 'audit-a35-' + secrets.token_hex(5)
    report = check.validate(directory)
    password = secrets.token_urlsafe(24)
    command = check.command
    def docker(image, port, *args, command_args=(), alias=None):
        network_args = ['--network', network] + (['--network-alias', alias] if alias else [])
        container = command('docker', 'run', '-d', *network_args, '-p', f'127.0.0.1::{port}', *args, image, *command_args)
        containers.append(container)
        return container, command('docker', 'port', container, str(port)).rsplit(':', 1)[1]
    def sql(database, query):
        return command('docker', 'exec', postgres, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'audit', '-d', database + '_db', '-Atqc', query)
    def metrics(service): return get(urls[service] + '/actuator/prometheus').decode()
    def metric(service, name, queue):
        matches = [line for line in metrics(service).splitlines() if line.startswith(name + '{') and f'queue="{queue}"' in line]
        assert len(matches) == 1, (service, name, queue, matches)
        return float(matches[0].rsplit(' ', 1)[1])
    def start(service):
        with socket.socket() as probe: probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
        env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'LANG', 'SYSTEMROOT', 'TEMP', 'TMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=str(port),
                   DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{service}_db', DB_USERNAME='audit', DB_PASSWORD=password,
                   REDIS_URL=f'redis://127.0.0.1:{redis_port}', PLATFORM_SERVICE_TOKEN=secrets.token_urlsafe(32),
                   MANAGEMENT_HEALTH_MAIL_ENABLED='false', OUTBOX_PUBLISHER_ENABLED='false',
                   MESSAGING_CONSUMER_ENABLED='false', PAYMENT_FOLLOW_UPS_ENABLED='false', NOTIFICATION_RETRY_ENABLED='false',
                   ORDER_SAGA_RECOVERY_ENABLED='false', ORDER_COUPONS_EXPIRY_ENABLED='false', PAYMENT_PROVIDER='disabled',
                   OBSERVABILITY_QUEUES_FIXED_DELAY_MS='500', SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4')
        handle = (directory / (service + '.log')).open('w'); handles.append(handle)
        jar = next((ROOT / (service + '-service') / 'target').glob('*-SNAPSHOT.jar'))
        process = subprocess.Popen(['java', '-Xms32m', '-Xmx160m', '-XX:TieredStopAtLevel=1', '-jar', str(jar)], env=env, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process); urls[service] = f'http://127.0.0.1:{port}'
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            assert process.poll() is None, service + ' exited; see ' + str(directory)
            try:
                if get(urls[service] + '/actuator/health'): return
            except OSError: pass
            time.sleep(.5)
        raise AssertionError(service + ' startup timeout')
    try:
        command('docker', 'network', 'create', network)
        postgres, db_port = docker('postgres:16-alpine', 5432, '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD=' + password)
        broker, redis_port = docker('redis:7-alpine', 6379)
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'audit'], capture_output=True).returncode == 0: break
            time.sleep(1)
        else: raise AssertionError('PostgreSQL startup timeout')
        services = ['order', 'payment', 'billing', 'notification']
        for service in services: command('docker', 'exec', postgres, 'createdb', '-U', 'audit', service + '_db')
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers: list(workers.map(start, services))
        queues = {'order': ['order_saga', 'order_reconciliation'], 'payment': ['payment_outbox', 'payment_follow_up', 'payment_follow_up_dlq', 'paid_without_invoice'], 'billing': ['invoice_outbox'], 'notification': ['notification_retry', 'notification_dlq']}
        for service, names in queues.items():
            for queue in names:
                poll(lambda: metric(service, 'platform_business_collector_up', queue), lambda v: v == 1)
                assert metric(service, 'platform_business_queue_count', queue) == 0
        print('PASS real services: migrations and all nine queue collectors on disposable PostgreSQL', flush=True)

        sql('order', "INSERT INTO orders(id,idempotency_key,product_id,quantity,unit_price,total_price,status,created_at,updated_at,customer_email,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,fulfillment_type) VALUES (1,'fixture-order',1,1,10,10,'CREATED',now()-interval '20 minutes',now(),'fixture@example.test','Test','Fixture','Test','00-001','Test','PL','STANDARD_COURIER',0,'HOME_DELIVERY'); INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES (1,'INVENTORY_RESERVATION_PENDING',now()-interval '20 minutes',now())")
        sql('payment', "INSERT INTO payments(order_id,idempotency_key,amount,currency,payment_method,status,created_at,updated_at,result_recorded_at) VALUES (1,'fixture-pay-1',10,'PLN','TEST','SUCCEEDED',now()-interval '1 day',now(),now()-interval '20 minutes'),(2,'fixture-pay-2',10,'PLN','TEST','SUCCEEDED',now(),now(),now()),(3,'fixture-pay-3',10,'PLN','TEST','PENDING',now(),now(),NULL); INSERT INTO payment_follow_ups(order_id,kind,status,created_at,updated_at) VALUES (1,'INVOICE_ISSUE','WAITING',now()-interval '20 minutes',now()),(3,'INVOICE_ISSUE','WAITING',now(),now())")
        sql('payment', "INSERT INTO payment_follow_ups(order_id,kind,status,created_at,updated_at) VALUES (2,'SHIPMENT_TRACKING','PENDING',now()-interval '20 minutes',now()),(3,'SHIPMENT_TRACKING','DEAD_LETTER',now()-interval '20 minutes',now()); INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,attempts,next_attempt_at,created_at,updated_at) VALUES ('fixture','PAYMENT_PENDING',1,10,'fixture@example.test','PENDING',0,now(),now()-interval '20 minutes',now())")
        sql('billing', "INSERT INTO invoice_outbox_events(event_id,payload,status,attempts,next_attempt_at,created_at) VALUES ('fixture','{}','PENDING',0,now(),now()-interval '20 minutes')")
        sql('notification', "INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at) VALUES ('PAYMENT_PENDING',1,'fixture@example.test','EMAIL','fixture','fixture','PENDING',now()-interval '20 minutes',now()); INSERT INTO notification_dead_letters(notification_id,event_type,source_id,recipient,channel,subject,message,status,failure_reason,attempt_count,created_at,updated_at) VALUES (1,'PAYMENT_PENDING',1,'fixture@example.test','EMAIL','fixture','fixture','OPEN','FIXTURE',3,now(),now())")
        for service, queue in [('order', 'order_saga'), ('payment', 'paid_without_invoice'), ('payment', 'payment_follow_up'), ('payment', 'payment_follow_up_dlq'), ('payment', 'payment_outbox'), ('billing', 'invoice_outbox'), ('notification', 'notification_retry'), ('notification', 'notification_dlq')]:
            poll(lambda: metric(service, 'platform_business_queue_count', queue), lambda v: v == 1)
            assert 'fixture@example.test' not in metrics(service)
        sql('order', "UPDATE order_sagas SET state='RECONCILIATION_REQUIRED'")
        poll(lambda: metric('order', 'platform_business_queue_count', 'order_reconciliation'), lambda v: v == 1)
        sql('order', "UPDATE order_sagas SET state='INVENTORY_RESERVATION_PENDING'")
        poll(lambda: metric('order', 'platform_business_queue_count', 'order_saga'), lambda v: v == 1)
        assert 1190 < metric('payment', 'platform_business_queue_oldest_age_seconds', 'paid_without_invoice') < 1300
        # Simulate collection failure; cached state must survive and recover after schema restoration.
        sql('billing', 'ALTER TABLE invoice_outbox_events RENAME TO fixture_offline_outbox')
        poll(lambda: metric('billing', 'platform_business_collector_up', 'invoice_outbox'), lambda v: v == 0)
        assert metric('billing', 'platform_business_queue_count', 'invoice_outbox') == 1
        sql('billing', 'ALTER TABLE fixture_offline_outbox RENAME TO invoice_outbox_events')
        poll(lambda: metric('billing', 'platform_business_collector_up', 'invoice_outbox'), lambda v: v == 1)
        report['realServiceCollectors'] = 9; report['databaseFailureRetainsSample'] = True
        report['invoiceRequestedAndPaymentTimingVerified'] = True
        print('PASS real SQL: old work, requested invoice timing, non-requested/pending exclusion and failed collector recovery', flush=True)

        # Relay the unmodified real Order metrics into the private container network.
        # This avoids publishing test Java ports to non-loopback interfaces on the host.
        (directory / 'fixture.py').write_text(FIXTURE)
        fixture, fixture_port = docker('python:3.13-alpine', 8080, '-v', str(directory.resolve()) + ':/fixture:ro', alias='metrics', command_args=('python', '/fixture/fixture.py'))
        fixture_url = 'http://127.0.0.1:' + fixture_port
        poll(lambda: subprocess.run(['docker', 'exec', fixture, 'python', '-c', 'import urllib.request; urllib.request.urlopen("http://localhost:8080/events")'], capture_output=True).returncode, lambda v: v == 0)
        prom_config = {'global': {'scrape_interval': '1s', 'evaluation_interval': '1s'},
            'rule_files': ['/etc/prometheus/rules/business.yml'],
            'alerting': {'alertmanagers': [{'static_configs': [{'targets': ['alerts:9093']}]}]},
            'scrape_configs': [{'job_name': 'mini-order-platform', 'metrics_path': '/actuator/prometheus', 'static_configs': [{'targets': ['metrics:8080'], 'labels': {'instance': 'order-service:8080'}}]}]}
        am_config = {'route': {'receiver': 'owned-fixture', 'group_by': ['alertname', 'instance', 'queue'], 'group_wait': '1s', 'group_interval': '1s', 'repeat_interval': '1h'}, 'receivers': [{'name': 'owned-fixture', 'webhook_configs': [{'url': 'http://metrics:8080/webhook', 'send_resolved': True}]}]}
        (directory / 'prometheus.yml').write_text(yaml.safe_dump(prom_config))
        (directory / 'alertmanager.yml').write_text(yaml.safe_dump(am_config))
        alerts, _ = docker(report['alertmanagerImage'], 9093, '-v', str(directory.resolve()) + ':/fixture:ro', alias='alerts', command_args=('--config.file=/fixture/alertmanager.yml', '--cluster.listen-address='))
        prom, prom_port = docker(report['prometheusImage'], 9090, '-v', str(directory.resolve()) + ':/fixture:ro', '-v', str((ROOT / 'infra/monitoring/prometheus/rules').resolve()) + ':/etc/prometheus/rules:ro', command_args=('--config.file=/fixture/prometheus.yml',))
        def delivery(status):
            post(fixture_url + '/metrics', metrics('order').encode())
            events = json.loads(get(fixture_url + '/events'))
            return [event for event in events if any(alert['labels']['alertname'] == 'BusinessQueueStalled' and alert['status'] == status for alert in event['alerts'])]
        print('Waiting for the original 2-minute rule hold and webhook delivery...', flush=True)
        firing = poll(lambda: delivery('firing'), bool, seconds=165)
        assert firing[-1]['alerts'][0]['annotations']['runbook'].endswith('#businessqueuestalled')
        print('PASS received BusinessQueueStalled firing webhook with runbook', flush=True)
        sql('order', "UPDATE order_sagas SET state='WAITING_FOR_PAYMENT'")
        resolved = poll(lambda: delivery('resolved'), bool, seconds=40)
        assert resolved
        (directory / 'webhook-events.json').write_text(json.dumps(json.loads(get(fixture_url + '/events')), indent=2))
        report.update(status='PASS', firingWebhook=True, resolvedWebhook=True, originalRuleHoldSeconds=120,
                      externalMessagesSent=False, targetEnvironmentAccepted=False)
        (directory / 'report.json').write_text(json.dumps(report, indent=2))
        print('PASS received resolved webhook; report: ' + str(directory / 'report.json'), flush=True)
    finally:
        for process in processes:
            if process.poll() is None: process.terminate()
        for process in processes:
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        for handle in handles: handle.close()
        for container in containers:
            result = subprocess.run(['docker', 'logs', container], capture_output=True, text=True)
            (directory / ('docker-' + container[:12] + '.log')).write_text(result.stdout + result.stderr)
        if containers: subprocess.run(['docker', 'rm', '-f', *containers], capture_output=True)
        subprocess.run(['docker', 'network', 'rm', network], capture_output=True)


if __name__ == '__main__':
    try: main()
    except subprocess.CalledProcessError as error:
        print(error.stdout, error.stderr)
        raise

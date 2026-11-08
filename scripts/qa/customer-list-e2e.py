#!/usr/bin/env python3
"""A20: real Customer/Gateway JARs, CC HTTP handler and disposable PostgreSQL."""
import base64
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import quality_gates

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('customer_list_http', ROOT / 'scripts/qa/access-control-e2e.py')
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)
sys.path.insert(0, str(ROOT / 'scripts/control-center/web'))
import server


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def main():
    directory = ROOT / 'tmp' / ('customer-list-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    processes, logs, postgres, local = [], [], None, None
    operator, service_token, password = (secrets.token_urlsafe(32) for _ in range(3))
    signing_key = secrets.token_bytes(48)
    urls = {service: f'http://127.0.0.1:{port()}' for service in ('customer-service', 'api-gateway')}

    def sql(query):
        return http.command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'customer_db', '-Atc', query)

    try:
        postgres = http.command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432',
                                '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD=' + password, 'postgres:16-alpine')
        db_port = http.command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-h', '127.0.0.1', '-U', 'audit'], capture_output=True).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Disposable PostgreSQL did not start')
        http.command('docker', 'exec', postgres, 'createdb', '-U', 'audit', 'customer_db')
        for service in ('customer-service', 'api-gateway'):
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith(('PLATFORM_', 'CUSTOMER_', 'QA_OPERATOR_'))}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[service].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/customer_db', DB_USERNAME='audit', DB_PASSWORD=password,
                       PLATFORM_AUTH_URL=urls['customer-service'], CUSTOMER_SERVICE_URL=urls['customer-service'],
                       PLATFORM_OPERATOR_TOKEN=operator, PLATFORM_SERVICE_TOKEN=service_token,
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps({'customer-service': service_token}),
                       CUSTOMER_JWT_SECRET=base64.b64encode(signing_key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / service / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {service} first'
            log = (directory / (service + '.log')).open('w'); logs.append(log)
            process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                       env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append(process)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'{service} exited; see {directory}')
                try:
                    with urllib.request.urlopen(urls[service] + '/actuator/health', timeout=2) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                time.sleep(.3)
            else:
                raise RuntimeError(f'{service} readiness timeout; see {directory}')

        sql("INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at) "
            "SELECT 'person-'||n||'@audit.example','fixture-hash',CASE WHEN n<3 THEN 'Jan' ELSE 'Anna' END,'Nazwisko '||n,"
            "'Testowa 1','00-001','Warszawa','Polska',true,now(),now() FROM generate_series(1,4) n")
        api = urls['api-gateway'] + '/api'
        contract = json.loads((ROOT / 'scripts/qa/contracts/api-gateway-v1.json').read_text())
        quality_gates.check_document(http.request(urls['customer-service'] + '/v3/api-docs'), contract['services']['customer'], 'Customer')
        http.request(urls['customer-service'] + '/error', expected=401)
        http.request(api + '/customers', expected=401)
        encoded = lambda value: base64.urlsafe_b64encode(json.dumps(value).encode()).rstrip(b'=').decode()
        customer_id = sql("SELECT id FROM customers WHERE email='person-1@audit.example'")
        unsigned = encoded({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encoded(
            {'typ': 'access', 'sub': customer_id, 'email': 'person-1@audit.example', 'exp': int(time.time()) + 600})
        customer_token = unsigned + '.' + base64.urlsafe_b64encode(hmac.new(signing_key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
        http.request(api + '/customers?search=Jan', token=customer_token, expected=403)
        rows = http.request(api + '/customers?size=2', token=operator)
        assert len(rows) == 2 and all('passwordHash' not in row for row in rows)

        server.GATEWAY_API = api
        # Only bearer attachment is adapted to the ephemeral loopback gateway port.
        # Production attachment on its configured ports is covered by credential tests.
        def authenticated_request(url, *args, **kwargs):
            assert url.startswith(api + '/'), 'Fixture must only contact its own Gateway'
            request = urllib.request.Request(url, *args, **kwargs)
            request.add_unredirected_header('Authorization', 'Bearer ' + operator)
            return request
        server.platform_request = authenticated_request
        local = server.create_server('127.0.0.1', 0)
        thread = threading.Thread(target=local.serve_forever, daemon=True); thread.start()
        authority = f'127.0.0.1:{local.server_port}'
        panel_token = local.panel_sessions.issue(authority)['token']
        headers = {'X-Control-Center-Token': panel_token}
        endpoint = 'http://' + authority + '/api/data/customers'
        page = http.request(endpoint + '?size=2', headers=headers)
        assert len(page['customers']) == 2 and page['hasMore']
        assert set(page['customers'][0]) == {'id', 'email', 'first_name', 'last_name', 'city', 'created_at'}
        older = http.request(endpoint + '?size=2&cursor=' + page['nextCursor'], headers=headers)
        assert len(older['customers']) == 2 and not older['hasMore']
        assert not {row['id'] for row in page['customers']}.intersection(row['id'] for row in older['customers'])
        filtered = http.request(endpoint + '?size=2&search=JAN', headers=headers)
        assert len(filtered['customers']) == 2 and all(row['first_name'] == 'Jan' for row in filtered['customers'])
        empty = http.request(endpoint + '?size=2&search=missing', headers=headers)
        assert empty['customers'] == []
        http.request(endpoint + '?size=201', headers=headers, expected=400)
        sql('ALTER TABLE customers RENAME COLUMN first_name TO isolated_schema_failure')
        try:
            failed = http.request(endpoint + '?size=2', headers=headers, expected=502)
            assert 'error' in failed and 'customers' not in failed
        finally:
            sql('ALTER TABLE customers RENAME COLUMN isolated_schema_failure TO first_name')
        assert http.request(endpoint + '?size=2', headers=headers)['customers']
        processes[0].terminate(); processes[0].wait(timeout=15)
        unavailable = http.request(endpoint + '?size=2', headers=headers, expected=503)
        assert 'error' in unavailable and 'customers' not in unavailable
        (directory / 'report.json').write_text(json.dumps({'status': 'PASS', 'httpChecks': http.CHECKS,
            'evidence': ['operator authorization', 'seeded PostgreSQL customers', 'bounded pages and filters',
                         'empty result', 'schema failure without empty success', 'recovery', 'service outage']}, indent=2))
        print(f'PASS A20: {http.CHECKS} HTTP checks; report: {directory / "report.json"}', flush=True)
    finally:
        if local:
            local.shutdown(); local.server_close()
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        for log in logs: log.close()
        if postgres: subprocess.run(['docker', 'rm', '-f', postgres], capture_output=True)


if __name__ == '__main__':
    main()

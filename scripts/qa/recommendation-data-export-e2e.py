#!/usr/bin/env python3
"""A43.20: bounded owner-only Recommendation export against isolated PostgreSQL and JARs."""
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
    directory = ROOT / 'tmp' / ('recommendation-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = [], [], []
    password, operator, service_token = (secrets.token_urlsafe(32) for _ in range(3))
    key = secrets.token_bytes(48)
    urls = {name: f'http://127.0.0.1:{port()}' for name in ('customer-service', 'recommendation-service', 'api-gateway')}

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
        for database in ('customer_db', 'recommendation_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in ('customer-service', 'recommendation-service', 'api-gateway'):
            database = 'recommendation_db' if name == 'recommendation-service' else 'customer_db'
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       RECOMMENDATION_SERVICE_URL=urls['recommendation-service'], PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=service_token,
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps({'recommendation-service': service_token}),
                       CUSTOMER_JWT_SECRET=base64.b64encode(key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       RECOMMENDATION_REQUEST_LOG_ENABLED='false', RECOMMENDATION_REQUEST_LOG_CLEANUP_ENABLED='false',
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
                   ('disabled@fixture.example','fixture-hash','Nie','Aktywna','Testowa 3','00-003','Kraków','Polska',false,now(),now()),
                   ('empty@fixture.example','fixture-hash','Pusty','Log','Testowa 4','00-004','Kraków','Polska',true,now(),now())
            """)
        sql('recommendation_db', """
            INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at)
            SELECT 1,CASE WHEN n%2=0 THEN NULL ELSE n END,'CUSTOMER_HISTORY',4,now() FROM generate_series(1,205) n;
            INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at)
            SELECT 2,1000+n,'CUSTOMER_HISTORY',3,now() FROM generate_series(1,2) n;
            INSERT INTO recommendation_requests(customer_id,source_product_id,strategy,result_count,requested_at)
            VALUES (NULL,99999,'PRODUCT_SIMILARITY',2,now())
            """)
        snapshot = 'SELECT json_agg(r ORDER BY id)::text FROM recommendation_requests r'
        before = sql('recommendation_db', snapshot)
        for name, prefix in [('recommendation-service', ''), ('api-gateway', '/api'), ('api-gateway', '/api/v1')]:
            client = qa.Client(urls[name], timeout=10)
            path = prefix + '/recommendations/me/data-export'
            owner = {'Authorization': 'Bearer ' + token(), 'X-Customer-Id': '2'}
            cursor, collected, pages = '0', [], 0
            while True:
                response = client.get(path + f'?size=100&cursor={cursor}&customerId=2', headers=owner)
                qa.status(response, 200, 'owner recommendation export')
                document = response.document(); pages += 1
                assert set(document) == {'schemaVersion', 'scope', 'complete', 'generatedAt', 'recordingEnabled', 'historyCoverage', 'items', 'hasMore', 'nextCursor', 'omissions'}
                assert document['schemaVersion'] == 1 and document['scope'] == 'recommendation-log-page' and document['complete'] is False
                assert len(document['items']) <= 100 and all(set(row) == {'requestId', 'sourceProductId', 'strategy', 'resultCount', 'requestedAt'} for row in document['items'])
                assert {item['scope'] for item in document['omissions']} == {'unloggedOrPurgedRequests', 'anonymousRequests', 'otherAccountData', 'changesDuringPagination'}
                assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
                assert response.headers['content-disposition'] == 'attachment; filename="recommendation-log-page.json"'
                assert response.headers['x-content-type-options'] == 'nosniff'
                assert document['recordingEnabled'] is False and document['historyCoverage'] == 'optional-retained-log'
                collected.extend(item['requestId'] for item in document['items'])
                if not document['hasMore']:
                    assert document['nextCursor'] == '0'; break
                cursor = document['nextCursor']; assert cursor.isdigit() and int(cursor) > 0
                assert pages < 10
            assert pages == 3 and collected == list(range(205, 0, -1))
            assert any(row['sourceProductId'] is None for row in client.get(path, headers=owner).document()['items'])
            other = qa.status(client.get(path, headers={'Authorization': 'Bearer ' + token(2, 'other@fixture.example')}), 200, 'other account').document()
            assert [item['requestId'] for item in other['items']] == [207, 206]
            empty = qa.status(client.get(path, headers={'Authorization': 'Bearer ' + token(4, 'empty@fixture.example')}), 200, 'empty retained log').document()
            assert empty['items'] == [] and empty['complete'] is False and empty['recordingEnabled'] is False
            assert empty['historyCoverage'] == 'optional-retained-log' and empty['hasMore'] is False
            for credential, expected in ((None, 401), (operator, 403), (service_token, 403), ('invalid', 401),
                                         (token(expired=True), 401), (token(3, 'disabled@fixture.example'), 401)):
                headers = {} if credential is None else {'Authorization': 'Bearer ' + credential}
                qa.status(client.get(path, headers=headers), expected, 'forbidden export actor')
            for query in ('?size=0', '?size=101', '?cursor=-1', '?cursor=9223372036854775808'):
                qa.status(client.get(path + query, headers=owner), 400, 'invalid pagination')
            # Gateway rejects methods outside its Recommendation mapping before forwarding.
            qa.status(client.get(path, method='POST', headers=owner), 403 if name == 'recommendation-service' else 405, 'wrong method')
            checks.append(name + prefix + ': 205 records/three pages, two owners, six denied actors, four invalid windows and headers')
        assert sql('recommendation_db', snapshot) == before
        checks.append('fixture recommendation rows unchanged')
        spec = qa.Client(urls['recommendation-service']).get('/v3/api-docs').document()
        contract = json.loads((ROOT / 'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec, contract['services']['recommendation'], 'Recommendation')
        assert {'bearerAuth': []} in spec['paths']['/recommendations/me/data-export']['get']['security']
        assert spec['components']['securitySchemes']['bearerAuth']['scheme'] == 'bearer'
        assert set(spec['components']['schemas']['RecommendationExportItem']['properties']) == {'requestId', 'sourceProductId', 'strategy', 'resultCount', 'requestedAt'}
        checks.append('OpenAPI contract, bearer scheme and explicit item allowlist')
        report = {'stage': 'A43.20', 'status': 'PASS', 'checks': checks, 'realUserDataAccessed': False,
                  'fullAccountExportComplete': False, 'deployment': False}
        (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'checks': len(checks), 'report': str(directory / 'report.json')}))
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

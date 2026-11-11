#!/usr/bin/env python3
"""A43.16: own disposable PostgreSQL and Customer/Gateway JARs; no live account access."""
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
    directory = ROOT / 'tmp' / ('account-data-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = [], [], []
    password, operator, service_token = (secrets.token_urlsafe(32) for _ in range(3))
    key = secrets.token_bytes(48)
    urls = {name: f'http://127.0.0.1:{port()}' for name in ('customer-service', 'api-gateway')}

    def sql(query):
        return command('docker', 'exec', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'fixture', '-d', 'a43_export_fixture', '-Atqc', query)

    def token(customer=1, email='owner@fixture.example', expired=False):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode(
            {'typ': 'access', 'sub': str(customer), 'email': email, 'exp': int(time.time()) + (-600 if expired else 600)})
        return unsigned + '.' + base64.urlsafe_b64encode(hmac.new(key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

    try:
        postgres = command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432',
                           '-e', 'POSTGRES_USER=fixture', '-e', 'POSTGRES_DB=a43_export_fixture',
                           '-e', 'POSTGRES_PASSWORD=' + password, 'postgres:16-alpine')
        db_port = command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'fixture'], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Fixture PostgreSQL startup timed out')
        for name in ('customer-service', 'api-gateway'):
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/a43_export_fixture', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       PLATFORM_OPERATOR_TOKEN=operator, PLATFORM_SERVICE_TOKEN=service_token,
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps({'customer-service': service_token}),
                       CUSTOMER_JWT_SECRET=base64.b64encode(key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {name} first'
            handle = (directory / (name + '.log')).open('w')
            handles.append(handle)
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

        sql("""
            INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            VALUES ('owner@fixture.example','fixture-password-hash','Jan','Żółć','Testowa 1','00-001','Warszawa','Polska',true,now(),now()),
                   ('other@fixture.example','fixture-password-hash','Anna','Inna','Testowa 2','00-002','Kraków','Polska',true,now(),now()),
                   ('disabled@fixture.example','fixture-password-hash','Nie','Aktywna','Testowa 3','00-003','Kraków','Polska',false,now(),now());
            INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,created_at,updated_at)
                VALUES (1,'fixture-totp-secret',true,now(),now());
            INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at,used_at)
                VALUES (1,repeat('a',64),now(),null),(1,repeat('b',64),now(),now());
            INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,public_key_credential_type,backup_state,created)
                VALUES ('fixture-credential','MQ',decode('01','hex'),0,false,false,'public-key',false,now());
            """)
        snapshot = """SELECT json_build_array(
            (SELECT json_agg(c ORDER BY id) FROM customers c),
            (SELECT json_agg(t ORDER BY id) FROM customer_totp_methods t),
            (SELECT json_agg(r ORDER BY id) FROM customer_recovery_codes r),
            (SELECT json_agg(w ORDER BY credential_id) FROM user_credentials w))::text"""
        before = sql(snapshot)
        for name, prefix in [('customer-service', ''), ('api-gateway', '/api'), ('api-gateway', '/api/v1')]:
            client = qa.Client(urls[name], timeout=10)
            path = prefix + '/customers/me/data-export'
            response = client.get(path + '?id=2&customerId=2&email=other@fixture.example', headers={
                'Authorization': 'Bearer ' + token(), 'X-Customer-Id': '2'})
            qa.status(response, 200, 'owner export')
            document = response.document()
            assert set(document) == {'schemaVersion', 'scope', 'complete', 'generatedAt', 'profile', 'security', 'omissions'}
            assert document['complete'] is False and document['scope'] == 'customer-profile-and-security-summary'
            assert {'scope':'redisAndRuntime','status':'reviewed-exclusions-and-manual-procedure'} in document['omissions']
            assert {'scope':'backupsAndExternalCopies','status':'separate-copy-procedures-A43.37-A43.39'} in document['omissions']
            assert document['profile']['id'] == 1 and document['profile']['lastName'] == 'Żółć'
            assert document['security'] == {'totpEnabled': True, 'passkeyCount': 1, 'unusedRecoveryCodeCount': 1}
            assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
            assert response.headers['content-disposition'] == 'attachment; filename="customer-profile-export.json"'
            assert response.headers['x-content-type-options'] == 'nosniff'
            for forbidden in ('fixture-password-hash', 'fixture-totp-secret', 'fixture-credential', 'other@fixture.example',
                              'passwordHash', 'secretCiphertext', 'verificationTokenHash', 'codeHash'):
                assert forbidden not in response.body.decode()
            for credential in (None, operator, service_token, 'invalid', token(expired=True), token(3, 'disabled@fixture.example')):
                headers = {} if credential is None else {'Authorization': 'Bearer ' + credential}
                qa.status(client.get(path, headers=headers), 401, 'forbidden export actor')
            second = client.get(path, headers={'Authorization': 'Bearer ' + token(2, 'other@fixture.example')}).document()
            assert second['profile']['id'] == 2 and second['security'] == {
                'totpEnabled': False, 'passkeyCount': 0, 'unusedRecoveryCodeCount': 0}
            checks.append(name + prefix + ': owner scope, partial manifest, headers, credential exclusion, six denied actors, separate subject')
        assert sql(snapshot) == before
        checks.append('profile/MFA/credential rows unchanged')
        spec = qa.Client(urls['customer-service']).get('/v3/api-docs').document()
        contract = json.loads((ROOT / 'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec, contract['services']['customer'], 'Customer')
        assert {'bearerAuth': []} in spec['paths']['/customers/me/data-export']['get']['security']
        assert set(spec['components']['schemas']['AccountExportProfile']['properties']) == {
            'id', 'email', 'firstName', 'lastName', 'street', 'postalCode', 'city', 'country', 'phoneNumber', 'enabled', 'createdAt', 'updatedAt'}
        checks.append('OpenAPI contract and explicit profile allowlist')
        report = {'stage': 'A43.16', 'status': 'PASS', 'checks': checks, 'realUserDataAccessed': False,
                  'fullAccountExportComplete': False, 'deployment': False}
        (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'checks': len(checks), 'report': str(directory / 'report.json')}))
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        for handle in handles:
            handle.close()
        if postgres:
            command('docker', 'rm', '-f', postgres)


if __name__ == '__main__':
    main()

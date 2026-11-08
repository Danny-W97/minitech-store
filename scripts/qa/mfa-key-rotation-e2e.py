#!/usr/bin/env python3
"""A22: real TOTP login across JWT/MFA rotations and restarts, on disposable infrastructure.

Requires packaged customer/notification services, Java 25, Docker and OpenSSL.
Uses only stdlib; no access to a developer's vault or databases.
"""
import base64
import argparse
import concurrent.futures
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('audit_http', ROOT / 'scripts/qa/access-control-e2e.py')
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)
request, command = http.request, http.command


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--with-session-checks', action='store_true')
    args = parser.parse_args()
    logdir = ROOT / 'tmp' / ('mfa-key-e2e-' + secrets.token_hex(4))
    logdir.mkdir(parents=True)
    logdir.chmod(0o700)
    processes, containers, handles = {}, [], []
    urls = {'customer-service': 'http://127.0.0.1:30087', 'notification-service': 'http://127.0.0.1:30086'}
    for url in urls.values():
        with socket.socket() as probe: probe.bind(('127.0.0.1', int(url.rsplit(':', 1)[1])))
    if args.with_session_checks:
        with socket.socket() as probe: probe.bind(('127.0.0.1', 30088))
    credentials = {service: secrets.token_urlsafe(32) for service in http.OFFSETS if service != 'api-gateway'}
    operator, password = secrets.token_urlsafe(32), secrets.token_urlsafe(24)
    customer_env = None

    def docker(image, port, *env):
        container = command('docker', 'run', '-d', '--rm', '-p', f'127.0.0.1::{port}', *env, image)
        containers.append(container)
        return container, command('docker', 'port', container, str(port)).rsplit(':', 1)[1]

    def start(service, env, label, project=None):
        jar = list((ROOT / (project or service) / 'target').glob('*-SNAPSHOT.jar'))
        assert len(jar) == 1, f'Build {service} first'
        log = (logdir / (label + '.log')).open('w')
        handles.append(log)
        process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jar[0])],
                                   env=env, stdout=log, stderr=subprocess.STDOUT)
        processes[service] = process
        return process

    def ready(service, process):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if process.poll() is not None: raise RuntimeError(f'{service} exited; see {logdir}')
            try:
                with urllib.request.urlopen(urls[service] + '/actuator/health', timeout=2) as response:
                    if response.status == 200: return
            except (OSError, urllib.error.URLError): pass
            time.sleep(1)
        raise RuntimeError(f'{service} health timeout; see {logdir}')

    def stop_customer():
        process = processes['customer-service']
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()

    def restart_customer(env, label, should_fail=False):
        stop_customer()
        process = start('customer-service', env, label)
        if should_fail:
            assert process.wait(timeout=90) != 0, 'Missing decryption key must block startup'
        else:
            ready('customer-service', process)
        print('PASS', label, flush=True)

    def rsa(label):
        private, public, raw = logdir / (label + '.der'), logdir / (label + '-public.der'), logdir / (label + '.pem')
        command('openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(raw))
        raw.chmod(0o600)
        command('openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', str(raw), '-outform', 'DER', '-out', str(private))
        private.chmod(0o600)
        command('openssl', 'pkey', '-inform', 'DER', '-in', str(private), '-pubout', '-outform', 'DER', '-out', str(public))
        encoded = (base64.b64encode(private.read_bytes()).decode(), base64.b64encode(public.read_bytes()).decode())
        private.unlink(); public.unlink(); raw.unlink()
        return encoded

    try:
        postgres, db_port = docker('postgres:16-alpine', 5432, '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD=' + password)
        redis_container, redis_port = docker('redis:7-alpine', 6379)
        _, mail_port = docker('axllent/mailpit:latest', 1025)
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'audit'], capture_output=True).returncode == 0: break
            time.sleep(1)
        else: raise RuntimeError('Isolated PostgreSQL failed to start')
        for db in ['customer_db', 'notification_db']: command('docker', 'exec', postgres, 'createdb', '-U', 'audit', db)
        for service in urls:
            env = {key: value for key, value in os.environ.items() if not key.startswith(('PLATFORM_', 'CUSTOMER_JWT_', 'CUSTOMER_MFA_', 'QA_OPERATOR_'))}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[service].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{service.replace("-service", "")}_db',
                       DB_USERNAME='audit', DB_PASSWORD=password, REDIS_URL=f'redis://127.0.0.1:{redis_port}',
                       MAIL_HOST='127.0.0.1', MAIL_PORT=mail_port, PLATFORM_AUTH_URL=urls['customer-service'],
                       NOTIFICATION_SERVICE_URL=urls['notification-service'], PLATFORM_SERVICE_TOKEN=credentials[service],
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            if service == 'customer-service':
                env.update(PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials), PLATFORM_OPERATOR_TOKEN=operator,
                           CUSTOMER_JWT_SECRET=base64.b64encode(secrets.token_bytes(48)).decode(),
                           CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode())
                customer_env = env
            start(service, env, service)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda service: ready(service, processes[service]), urls))
        api = urls['customer-service']
        login = dict(email='mfa@audit.example', password='AuditPass-123!')
        request(api + '/customers/register', 'POST', dict(login, firstName='Mfa', lastName='Tester',
                street='Testowa 1', postalCode='00-001', city='Warszawa', country='Polska'), expected=201)
        def sql(query): return command('docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'customer_db', '-Atc', query)
        sql("UPDATE customers SET enabled=true WHERE email='mfa@audit.example'")
        initial = request(api + '/customers/login', 'POST', login)
        customer_id = initial['customer']['id']
        enrollment = request(api + '/customers/mfa/totp/enroll', 'POST', token=initial['accessToken'])
        secret = enrollment['secret']
        def code(step, value=None):
            value = secret if value is None else value
            raw = base64.b32decode(value + '=' * (-len(value) % 8))
            digest = hmac.new(raw, struct.pack('>Q', step), hashlib.sha1).digest()
            offset = digest[-1] & 15
            return f'{(struct.unpack(">I", digest[offset:offset+4])[0] & 0x7fffffff) % 1000000:06d}'
        enrolled_step = int(time.time()) // 30
        recovery = request(api + '/customers/mfa/totp/confirm', 'POST', {'code': code(enrolled_step)}, token=initial['accessToken'])['codes']
        def stored(): return sql(f'SELECT secret_ciphertext FROM customer_totp_methods WHERE customer_id={customer_id}')
        def totp_login():
            last = int(sql(f'SELECT last_used_time_step FROM customer_totp_methods WHERE customer_id={customer_id}'))
            while last >= int(time.time()) // 30 + 1: time.sleep(1)
            step = max(last + 1, int(time.time()) // 30)
            challenge = request(api + '/customers/login', 'POST', login, expected=202)
            assert challenge['mfaRequired'] is True
            return request(api + '/customers/mfa/login/verify', 'POST', {'challengeId': challenge['challengeId'], 'code': code(step)})
        def recovery_login(index):
            challenge = request(api + '/customers/login', 'POST', login, expected=202)
            assert challenge['mfaRequired'] is True
            return request(api + '/customers/mfa/login/verify', 'POST', {'challengeId': challenge['challengeId'], 'code': recovery[index]})
        assert stored().startswith('v1:mfa-v1:')
        # Reproduce a pre-A22 unversioned row derived from the old RSA signing material.
        old_private, old_public = rsa('old-jwt')
        legacy = subprocess.run(['java', str(ROOT / 'scripts/qa/fixtures/LegacyMfaCipher.java')],
                input=old_private + '\n' + secret + '\n', check=True, text=True, capture_output=True).stdout.strip()
        sql(f"UPDATE customer_totp_methods SET secret_ciphertext='{legacy}' WHERE customer_id={customer_id}")
        first_private, first_public = rsa('first-jwt')
        customer_env.update(CUSTOMER_MFA_LEGACY_KEY_BASE64=base64.b64encode(hashlib.sha256(old_private.encode()).digest()).decode(),
                            CUSTOMER_JWT_ACTIVE_KID='rsa-first', CUSTOMER_JWT_PRIVATE_KEY_BASE64=first_private,
                            CUSTOMER_JWT_PUBLIC_KEYS_JSON=json.dumps({'rsa-old': old_public, 'rsa-first': first_public}))
        restart_customer(customer_env, 'jwt-rotation-and-legacy-migration')
        assert stored().startswith('v1:mfa-v1:')
        assert int(sql(f'SELECT last_used_time_step FROM customer_totp_methods WHERE customer_id={customer_id}')) == enrolled_step
        first = totp_login()
        assert json.loads(base64.urlsafe_b64decode(first['accessToken'].split('.')[0] + '=='))['kid'] == 'rsa-first'
        second_private, second_public = rsa('second-jwt')
        customer_env.update(CUSTOMER_JWT_ACTIVE_KID='rsa-second', CUSTOMER_JWT_PRIVATE_KEY_BASE64=second_private,
                            CUSTOMER_JWT_PUBLIC_KEYS_JSON=json.dumps({'rsa-first': first_public, 'rsa-second': second_public}))
        before = stored()
        restart_customer(customer_env, 'second-jwt-rotation-retains-mfa-and-old-tokens')
        assert stored() == before
        request(api + '/customers/me', token=first['accessToken'])
        second = recovery_login(0)
        old_mfa = customer_env['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64']
        customer_env.update(CUSTOMER_MFA_ACTIVE_KID='mfa-second', CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode())
        restart_customer(customer_env, 'premature-mfa-key-retirement-blocks-startup', should_fail=True)
        assert stored() == before
        customer_env['CUSTOMER_MFA_PREVIOUS_KEYS_JSON'] = json.dumps({'mfa-v1': old_mfa})
        restart_customer(customer_env, 'mfa-key-rotation-migrates-existing-totp')
        assert stored().startswith('v1:mfa-second:')
        third = totp_login()
        migrated = stored()
        customer_env.pop('CUSTOMER_MFA_PREVIOUS_KEYS_JSON')
        customer_env.pop('CUSTOMER_MFA_LEGACY_KEY_BASE64')
        customer_env['CUSTOMER_JWT_PUBLIC_KEYS_JSON'] = json.dumps({'rsa-second': second_public})
        restart_customer(customer_env, 'retire-old-keys-after-successful-migration')
        assert stored() == migrated
        assert request(api + '/customers/mfa/status', token=third['accessToken'])['totpEnabled'] is True
        request(api + '/customers/me', token=first['accessToken'], expected=401)
        request(api + '/customers/me', token=second['accessToken'])
        recovery_login(1)
        print(f'PASS: A22, {http.CHECKS} HTTP checks; TOTP survives JWT/MFA rotation, restart and legacy migration; premature key retirement fails atomically.', flush=True)
        if args.with_session_checks:
            from auth_session_checks import run_checks
            urls['customer-replica'] = 'http://127.0.0.1:30088'
            replica_env = dict(customer_env, SERVER_PORT='30088')
            ready('customer-replica', start('customer-replica', replica_env, 'customer-replica', project='customer-service'))
            def delete_enrollment(customer):
                command('docker', 'exec', redis_container, 'redis-cli', 'DEL', f'customer:mfa:enrollment:{customer}')
            run_checks(http, urls, login, secret, recovery, customer_id, sql, delete_enrollment, code)
        for log in logdir.glob('*.log'):
            content = log.read_text(errors='replace')
            for sensitive in [secret, old_private, first_private, second_private, old_mfa, customer_env['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64']]:
                assert sensitive not in content, f'Secret leaked to {log.name}'
    finally:
        for process in processes.values():
            if process.poll() is None: process.terminate()
        for process in processes.values():
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for handle in handles: handle.close()
        for pattern in ('*.der', '*.pem'):
            for key_file in logdir.glob(pattern): key_file.unlink()
        for container in containers: subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
        print('Isolated resources removed; logs:', logdir, flush=True)


if __name__ == '__main__': main()

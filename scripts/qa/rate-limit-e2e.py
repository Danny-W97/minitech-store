#!/usr/bin/env python3
"""A24: packaged gateways, a disposable Redis, and an HTTP backend recording effects.

Uses only temporary resources. Never attaches to the developer's broker/services.
"""
import base64
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CHECKS = 0
ORIGIN = 'http://127.0.0.1:18070'


def main():
    global CHECKS
    logdir = ROOT / 'tmp' / ('rate-limit-e2e-' + secrets.token_hex(4))
    logdir.mkdir(parents=True)
    processes, handles = [], []
    redis = None
    effects = {'GET': 0, 'POST': 0}
    effect_lock = threading.Lock()
    class Backend(BaseHTTPRequestHandler):
        def serve(self):
            with effect_lock:
                effects[self.command] += 1
                count = effects[self.command]
            body = json.dumps({'id': count, 'path': self.path}).encode()
            self.send_response(201 if self.command == 'POST' else 200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body)
        do_GET = do_POST = serve
        def log_message(self, *args): pass
    backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    backend_url = f'http://127.0.0.1:{backend.server_port}'
    urls = [f'http://127.0.0.1:{port}' for port in [31080, 31081, 31082]]
    for port in [31080, 31081, 31082]:
        with socket.socket() as probe: probe.bind(('127.0.0.1', port))
    def command(*args): return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()
    def redis_cmd(*args): return command('docker', 'exec', redis, 'redis-cli', '--raw', *map(str, args))
    def request(index, path='/api/products/1', method='GET', forwarded=None, expected=200, extra=None):
        global CHECKS
        headers = {'Origin': ORIGIN}
        if forwarded is not None: headers['X-Forwarded-For'] = forwarded
        headers.update(extra or {})
        data = b'{}' if method == 'POST' else None
        if data is not None: headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(urls[index] + path, headers=headers, data=data, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                status, returned, body = response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            status, returned, body = error.code, error.headers, error.read()
        accepted = expected if isinstance(expected, tuple) else (expected,)
        assert status in accepted, f'{method} {path}: expected {accepted}, got {status}; {body[:150]!r}'
        if path.startswith('/api/'):
            assert returned.get('Access-Control-Allow-Origin') == ORIGIN, f'CORS missing for {status}'
            assert 'Retry-After' in returned.get('Access-Control-Expose-Headers', '')
        if status == 429:
            assert int(returned['Retry-After']) >= 1
            assert returned['X-RateLimit-Remaining'] == '0'
        CHECKS += 1
        return status, returned
    def clear(): redis_cmd('FLUSHDB')  # Only this run's disposable Redis container.
    def key(ip): return 'security:rate-limit:v2:' + base64.urlsafe_b64encode(ip.encode()).rstrip(b'=').decode()
    try:
        redis = command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::6379', 'redis:7-alpine')
        port = command('docker', 'port', redis, '6379').rsplit(':', 1)[1]
        jars = list((ROOT / 'api-gateway/target').glob('*-SNAPSHOT.jar'))
        assert len(jars) == 1, 'Package api-gateway first'
        for index, url in enumerate(urls):
            env = {key: value for key, value in os.environ.items() if not key.startswith(('GATEWAY_', 'SERVER_FORWARD_', 'PLATFORM_', 'CUSTOMER_JWT_', 'CUSTOMER_MFA_'))}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=url.rsplit(':', 1)[1], REDIS_URL=f'redis://127.0.0.1:{port}',
                       GATEWAY_RATE_LIMIT_ENABLED='true', GATEWAY_RATE_LIMIT_REQUESTS_PER_WINDOW='10',
                       GATEWAY_RATE_LIMIT_WINDOW_SECONDS='10', GATEWAY_RATE_LIMIT_FAIL_OPEN='true' if index == 1 else 'false',
                       GATEWAY_REDIS_TIMEOUT='250ms', GATEWAY_REDIS_CONNECT_TIMEOUT='250ms',
                       GATEWAY_TRUSTED_PROXY_CIDRS='127.0.0.1/32,10.0.0.8/32' if index == 2 else '')
            for service in ['PRODUCT', 'ORDER', 'INVENTORY', 'PAYMENT', 'CUSTOMER']:
                env[service + '_SERVICE_URL'] = backend_url
            log = (logdir / f'gateway-{index}.log').open('w'); handles.append(log)
            processes.append(subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                             env=env, stdout=log, stderr=subprocess.STDOUT))
        def ready(index):
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if processes[index].poll() is not None: raise RuntimeError(f'Gateway {index} exited; see {logdir}')
                try:
                    with urllib.request.urlopen(urls[index] + '/actuator/health', timeout=2) as response:
                        if response.status == 200: return
                except (OSError, urllib.error.URLError): pass
                time.sleep(1)
            raise RuntimeError(f'Gateway {index} readiness timeout; see {logdir}')
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: list(pool.map(ready, range(3)))
        print('READY three packaged gateways and isolated Redis', flush=True)
        clear()
        for index in range(10): request(index % 2, path=f'/api/products/{index}', forwarded=f'198.51.100.{index}')
        request(0, '/api/v1/products/100', forwarded='203.0.113.1', expected=429)
        request(1, '/api/orders', method='POST', forwarded='203.0.113.2', expected=429)
        request(0, '/%61pi/products/101', forwarded='203.0.113.3', expected=429)
        assert int(redis_cmd('DBSIZE')) == 1
        assert 0 < int(redis_cmd('PTTL', key('127.0.0.1'))) <= 10000
        request(0, '/api/orders', method='OPTIONS', extra={'Access-Control-Request-Method': 'POST'})
        request(0, '/actuator/health')
        print('PASS untrusted XFF, changing IDs/methods/API aliases/encoding, CORS and preflight', flush=True)
        clear()
        with effect_lock: before = effects['GET']
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            statuses = list(pool.map(lambda index: request(index % 2, f'/api/products/{index}', expected=(200, 429))[0], range(64)))
        assert statuses.count(200) == 10 and statuses.count(429) == 54
        with effect_lock: assert effects['GET'] - before == 10
        ttl = int(redis_cmd('PTTL', key('127.0.0.1')))
        assert 0 < ttl <= 10000 and redis_cmd('GET', key('127.0.0.1')) == '11'
        time.sleep(ttl / 1000 + .15)
        _, headers = request(0)
        assert headers['X-RateLimit-Remaining'] == '9'
        clear(); redis_cmd('SET', key('127.0.0.1'), 10)
        request(0, expected=429)
        assert 0 < int(redis_cmd('PTTL', key('127.0.0.1'))) <= 10000
        clear(); redis_cmd('SET', key('127.0.0.1'), 'broken')
        request(0, expected=429)
        assert int(redis_cmd('PTTL', key('127.0.0.1'))) > 0
        print('PASS shared atomic budget on two JVMs, TTL expiry/repair and bounded counters', flush=True)
        clear()
        for index in range(10): request(2, forwarded=f'203.0.113.{index}, 198.51.100.9, 10.0.0.8')
        request(2, forwarded='203.0.113.111, 198.51.100.9, 10.0.0.8', expected=429,
                extra={'Forwarded': 'for=203.0.113.200', 'X-Real-IP': '203.0.113.201'})
        assert redis_cmd('GET', key('198.51.100.9')) == '11'
        request(2, forwarded='198.51.100.10, 10.0.0.8')
        request(2, forwarded='bad-host, 198.51.100.9', expected=400)
        request(2, forwarded='x' * 2100, expected=400)
        assert int(redis_cmd('DBSIZE')) == 2
        for counter in redis_cmd('KEYS', 'security:rate-limit:v2:*').splitlines(): assert int(redis_cmd('PTTL', counter)) > 0
        print('PASS trusted proxy chain, leftmost spoof rejection, malformed headers and per-client isolation', flush=True)
        command('docker', 'stop', '-t', '1', redis)
        with effect_lock: before = effects['POST']
        request(0, '/api/orders', method='POST', expected=503)
        with effect_lock: assert effects['POST'] == before
        request(1, '/api/orders', method='POST', expected=201)
        with effect_lock: assert effects['POST'] == before + 1
        print('PASS Redis outage: fail-closed has no effect; fail-open executes exactly once; CORS preserved', flush=True)
        print(f'PASS: A24, {CHECKS} HTTP checks on three real gateways, shared Redis and a recording HTTP backend.', flush=True)
    finally:
        for process in processes:
            if process.poll() is None: process.terminate()
        for process in processes:
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        backend.shutdown(); backend.server_close()
        for handle in handles: handle.close()
        if redis: subprocess.run(['docker', 'rm', '-f', redis], capture_output=True)
        print('Isolated resources removed; logs:', logdir, flush=True)


if __name__ == '__main__': main()

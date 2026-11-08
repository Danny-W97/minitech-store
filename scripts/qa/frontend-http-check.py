#!/usr/bin/env python3
"""Measure static frontend HTTP using the pinned Nginx in an owned, network-isolated container."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import subprocess
import socket
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ('/index.html', '/catalog.html', '/app.js', '/store-account.js', '/store-checkout.js', '/styles.css')


def run(*args, check=True):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)


def measure(config, image):
    # Only this newly created container is removed; no compose or application resources are used.
    temporary = tempfile.TemporaryDirectory(prefix='a40-http-')
    fixture = Path(temporary.name)
    sockets = fixture / 'socket';sockets.mkdir();sockets.chmod(0o777)
    text = config.read_text()
    assert text.count('listen 8080;') == 1, 'Unrecognized fixture listen directive'
    fixture_config = fixture / 'nginx.conf'
    fixture_config.write_text(text.replace('listen 8080;', 'listen 8080;\n    listen unix:/tmp/a40-probe/http.sock;'))
    container = run('docker', 'run', '-d', '--pull=never', '--network=none', '--read-only',
                    '--user=10001:10001', '--tmpfs=/tmp', '--tmpfs=/var/cache/nginx',
                    '--label=audit.fixture=a40-http',
                    '--add-host=api-gateway:192.0.2.1', '--entrypoint=/bin/sh',
                    '-v', f'{fixture_config}:/etc/nginx/conf.d/default.conf:ro',
                    '-v', f'{sockets}:/tmp/a40-probe',
                    '-v', f'{ROOT / "deploy/docker/nginx.conf"}:/etc/nginx/nginx.conf:ro',
                    '-v', f'{ROOT / "frontend"}:/usr/share/nginx/html:ro',
                    image, '-c', 'umask 000; exec nginx -g "daemon off;"').stdout.decode().strip()
    assert re.fullmatch(r'[a-f0-9]{64}', container), 'Unrecognized owned container ID'
    try:
        def get(path, headers=(), head=False):
            # The extra Unix listener exists only in this fixture. It avoids publishing
            # a TCP port or depending on the base image's limited diagnostic clients.
            request = '\r\n'.join([('HEAD' if head else 'GET') + ' ' + path + ' HTTP/1.1',
                                   'Host: localhost', 'Connection: close', *headers, '', '']).encode()
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
                peer.settimeout(5);peer.connect(str(sockets / 'http.sock'));peer.sendall(request)
                chunks = []
                while chunk := peer.recv(65536):chunks.append(chunk)
                raw = b''.join(chunks)
            raw_headers, separator, body = raw.partition(b'\r\n\r\n')
            assert separator, 'Missing HTTP headers in isolated fixture'
            lines = raw_headers.decode()
            status = re.match(r'HTTP/1\.[01] (\d{3})', lines)
            assert status, lines
            parsed = {k.lower(): v.strip() for k, v in re.findall(r'^([\w-]+):[ \t]*(.*?)\r?$', lines, re.M)}
            if not head and parsed.get('transfer-encoding') == 'chunked':
                chunks = []
                while body:
                    length, _, body = body.partition(b'\r\n')
                    n = int(length.split(b';', 1)[0], 16)
                    if n == 0:
                        break
                    assert body[n:n+2] == b'\r\n', 'Invalid chunk framing'
                    chunks.append(body[:n]);body = body[n+2:]
                body = b''.join(chunks)
            return {'status': int(status.group(1)), 'headers': parsed, 'body': body}

        for _ in range(40):
            ready = run('docker', 'exec', container, 'wget', '-q', '-O', '-', 'http://127.0.0.1:8080/healthz', check=False)
            if ready.returncode == 0:
                break
            time.sleep(.1)
        else:
            raise AssertionError('Isolated Nginx did not become ready')
        results = {}
        for path in ASSETS:
            plain = get(path)
            compressed = get(path, ('Accept-Encoding: gzip',))
            assert plain['status'] == compressed['status'] == 200, path
            body = compressed['body']
            if compressed['headers'].get('content-encoding') == 'gzip':
                body = gzip.decompress(body)
            assert body == plain['body'], 'Compression changed asset content: ' + path
            etag = plain['headers'].get('etag')
            assert etag, 'Missing validator: ' + path
            conditional = get(path, ('If-None-Match: ' + etag,))
            assert conditional['status'] == 304 and not conditional['body'], path
            results[path] = {'plainBytes': len(plain['body']), 'encodedBytes': len(compressed['body']),
                             'headers': compressed['headers'], 'sha256': hashlib.sha256(body).hexdigest(),
                             'conditionalStatus': conditional['status'], 'conditionalBytes': len(conditional['body'])}
        for path in ('/a40-missing.js', '/a40-missing.css', '/order-detail.html', '/api/a40-isolated-probe'):
            r = get(path, ('Accept-Encoding: gzip',))
            results[path] = {'status': r['status'], 'headers': r['headers']}
        # HEAD checks only. Photo contents and cache policy are outside this implementation.
        photos = sorted((ROOT / 'frontend').rglob('*.png'))
        if photos:
            path = '/' + photos[0].relative_to(ROOT / 'frontend').as_posix()
            r = get(path, ('Accept-Encoding: gzip',), head=True)
            results['photoHead'] = {'path': path, 'status': r['status'], 'headers': r['headers']}
        return results
    finally:
        run('docker', 'rm', '-f', container)
        temporary.cleanup()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline-config', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'tmp/frontend-http-report.json')
    args = parser.parse_args()
    dockerfile = (ROOT / 'deploy/docker/frontend.Dockerfile').read_text()
    image = re.search(r'^FROM (nginx:[^\s]+@sha256:[a-f0-9]{64})$', dockerfile, re.M).group(1)
    report = {'status': 'FAILED', 'scope': 'isolated static HTTP; not browser LCP/CLS/INP or deployed environment',
              'image': image, 'photoChanges': False, 'actualApplicationRequests': False}
    try:
        before = measure(args.baseline_config, image) if args.baseline_config else None
        after = measure(ROOT / 'deploy/docker/nginx.frontend.conf', image)
        report.update(before=before, after=after)
        for path in ASSETS:
            r = after[path]
            assert r['headers'].get('content-encoding') == 'gzip', path
            assert r['headers'].get('vary') == 'Accept-Encoding', path
            assert r['encodedBytes'] < r['plainBytes'], path
            policy = r['headers'].get('cache-control', '')
            assert policy == ('no-store' if path.endswith('.html') else 'no-cache, must-revalidate'), (path, policy)
            assert 'immutable' not in policy, 'Existing query-string versions are not content hashes'
            if before:
                assert r['sha256'] == before[path]['sha256'], path
        for path in ('/a40-missing.js', '/a40-missing.css'):
            assert after[path]['status'] == 404, 'Missing asset must not return the HTML shell'
            assert after[path]['headers'].get('cache-control') == 'no-store', path
        assert after['/order-detail.html']['headers'].get('x-robots-tag') == 'noindex, nofollow'
        assert after['/api/a40-isolated-probe']['status'] == 502  # No upstream in the fixture.
        assert 'content-encoding' not in after['/api/a40-isolated-probe']['headers']
        if before and 'photoHead' in before:
            for k in ('cache-control', 'expires', 'content-encoding', 'etag', 'content-type'):
                assert before['photoHead']['headers'].get(k) == after['photoHead']['headers'].get(k), k
        report.update(status='PASS', before=before, after=after,
                      measuredPlainBytes=sum(after[p]['plainBytes'] for p in ASSETS),
                      measuredEncodedBytes=sum(after[p]['encodedBytes'] for p in ASSETS))
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('before', 'after')}))


if __name__ == '__main__':
    main()

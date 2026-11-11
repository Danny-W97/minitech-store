#!/usr/bin/env python3
"""Encrypted 14-database + Redis sets and isolated, non-destructive restore drills.

Sequential snapshots are explicitly NOT a distributed transaction checkpoint.
No command restores into an existing container, network or user database.
"""
import argparse
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
POSTGRES_IMAGE = 'postgres:16-alpine'
REDIS_IMAGE = 'redis:7-alpine'
CORE_TABLES = {
    'product': 'products', 'order': 'orders', 'inventory': 'inventory_reservations',
    'payment': 'payments', 'notification': 'notifications', 'customer': 'customers',
    'delivery': 'shipments', 'promotion': 'promotion_campaigns', 'wishlist': 'wishlist_items',
    'review': 'reviews', 'recommendation': 'recommendation_requests', 'chat': 'chat_messages',
    'returns': 'return_requests', 'billing': 'invoices',
}


def aliases():
    return sorted(component['database'] for component in json.loads((ROOT / 'deploy/components.json').read_text())['components'] if component['database'])


def now():
    return datetime.now(timezone.utc).isoformat()


class CaptureClock:
    def __init__(self):self.utc=datetime.now(timezone.utc);self.monotonic=time.monotonic()
    def now(self):return (self.utc+timedelta(seconds=max(0,time.monotonic()-self.monotonic))).isoformat()


def private_directory(path):
    path = Path(path)
    if path.is_symlink(): raise ValueError('Symlink directory is not allowed')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path.resolve()


def run(*args, input=None, env=None, timeout=120):
    result = subprocess.run(args, input=input, env=env, capture_output=True, timeout=timeout)
    if result.returncode:
        # SQL errors and Redis data must not leak into CC output or shared logs.
        raise RuntimeError('Command failed: ' + Path(args[0]).name + ' (exit ' + str(result.returncode) + ')')
    return result.stdout.decode().strip()


def stream(producer, consumer, output=None, timeout=1800):
    """Pipe dump/decryption directly; check BOTH exit codes, never persist plaintext."""
    with tempfile.TemporaryFile() as producer_error, tempfile.TemporaryFile() as consumer_error:
        first = subprocess.Popen(producer, stdout=subprocess.PIPE, stderr=producer_error)
        second = None
        try:
            second = subprocess.Popen(consumer, stdin=first.stdout, stdout=output or subprocess.DEVNULL, stderr=consumer_error)
            first.stdout.close()
            second_status = second.wait(timeout=timeout)
            first_status = first.wait(timeout=30)
            if first_status or second_status:
                raise RuntimeError('Stream failed (source=' + str(first_status) + ', destination=' + str(second_status) + ')')
        finally:
            for process in (second, first):
                if process is not None and process.poll() is None:
                    process.kill(); process.wait(timeout=15)
            if first.stdout: first.stdout.close()


def write_encrypted(path, data, recipient):
    with path.open('xb') as output:
        path.chmod(0o600)
        result = subprocess.run(['age', '-r', recipient], input=data, stdout=output, stderr=subprocess.DEVNULL, timeout=30)
    if result.returncode: raise RuntimeError('Manifest encryption failed')


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while block := source.read(1024 * 1024): digest.update(block)
    return digest.hexdigest()


def artifact_names():
    return {alias: alias + '.dump.age' for alias in aliases()} | {'redis': 'redis.rdb.age'}


def validate_manifest(manifest):
    expected = artifact_names()
    if manifest.get('format') != 1 or manifest.get('consistency') != 'independent-snapshots':
        raise ValueError('Unsupported manifest format or consistency claim')
    if not re.fullmatch(r'checkpoint-\d{8}T\d{6}Z-[0-9a-f]{8}', str(manifest.get('id', ''))):
        raise ValueError('Invalid checkpoint ID')
    started = datetime.fromisoformat(manifest['startedAt'])
    finished = datetime.fromisoformat(manifest['finishedAt'])
    if started.tzinfo is None or finished.tzinfo is None or finished < started:
        raise ValueError('Invalid capture interval')
    artifacts = manifest.get('artifacts')
    if not isinstance(artifacts, dict) or set(artifacts) != set(expected):
        raise ValueError('A complete set requires every database and Redis')
    for name, filename in expected.items():
        value = artifacts[name]
        if value.get('file') != filename or not re.fullmatch('[0-9a-f]{64}', str(value.get('sha256', ''))):
            raise ValueError('Invalid artifact path or hash')
        if type(value.get('bytes')) is not int or value['bytes'] < 32:
            raise ValueError('Invalid artifact length')
        begin = datetime.fromisoformat(value['startedAt']); end = datetime.fromisoformat(value['finishedAt'])
        if begin.tzinfo is None or end.tzinfo is None or not started <= begin <= end <= finished:
            raise ValueError('Artifact interval is outside checkpoint')
    return manifest


def load_manifest(bundle, identity, verify_hashes=True):
    bundle = Path(bundle)
    if bundle.is_symlink() or not bundle.is_dir(): raise ValueError('Bundle must be a real directory')
    manifest_file = bundle / 'manifest.json.age'
    if manifest_file.is_symlink() or not manifest_file.is_file() or manifest_file.stat().st_size > 1024 * 1024:
        raise ValueError('Missing or invalid encrypted completion manifest')
    raw = run('age', '-d', '-i', str(identity), str(manifest_file), timeout=30)
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('Duplicate manifest key')
            result[key] = value
        return result
    manifest = validate_manifest(json.loads(raw, object_pairs_hook=unique))
    allowed = set(artifact_names().values()) | {'manifest.json.age'}
    if {path.name for path in bundle.iterdir()} != allowed:
        raise ValueError('Incomplete set or unexpected file in bundle')
    for value in manifest['artifacts'].values():
        path = bundle / value['file']
        if path.is_symlink() or not path.is_file() or path.stat().st_size != value['bytes']:
            raise ValueError('Missing or invalid artifact')
        if verify_hashes and sha256(path) != value['sha256']:
            raise ValueError('Artifact checksum mismatch')
    return manifest


def validate_sources(sources):
    if set(sources) != {'databases', 'redis'} or set(sources['databases']) != set(aliases()):
        raise ValueError('Sources must include all databases and Redis')
    for alias, source in sources['databases'].items():
        if not re.fullmatch('[a-f0-9]{12,64}', source['container']): raise ValueError('Use resolved container IDs')
        if source['database'] != alias + '_db' or not re.fullmatch('[a-z_][a-z0-9_]{0,62}', source['user']):
            raise ValueError('Invalid source database or user')
    if not re.fullmatch('[a-f0-9]{12,64}', sources['redis']['container']): raise ValueError('Invalid Redis container ID')
    return sources


def capture(sources, recipient, destination):
    clock=CaptureClock()
    sources = validate_sources(sources)
    destination = private_directory(destination)
    identifier = 'checkpoint-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + secrets.token_hex(4)
    bundle = destination / identifier
    bundle.mkdir(mode=0o700)
    manifest = {'format': 1, 'id': identifier, 'startedAt': clock.now(), 'consistency': 'independent-snapshots', 'artifacts': {}}
    try:
        for alias in aliases() + ['redis']:
            started = clock.now()
            path = bundle / artifact_names()[alias]
            if alias == 'redis':
                source = sources['redis']
                info = run('docker', 'exec', source['container'], 'redis-cli', '--raw', 'INFO', 'server')
                if not re.search(r'(?m)^redis_version:7\.', info): raise ValueError('Only Redis 7 is supported by this drill')
                producer = ['docker', 'exec', source['container'], 'redis-cli', '--rdb', '-']
            else:
                source = sources['databases'][alias]
                version = int(run('docker', 'exec', source['container'], 'psql', '-X', '-U', source['user'], '-d', source['database'], '-Atqc', 'SHOW server_version_num'))
                if not 160000 <= version < 170000: raise ValueError('Only PostgreSQL 16 is supported by this drill')
                producer = ['docker', 'exec', source['container'], 'pg_dump', '--format=custom', '--no-owner', '--no-acl', '--username=' + source['user'], '--dbname=' + source['database']]
            with path.open('xb') as output:
                path.chmod(0o600)
                stream(producer, ['age', '-r', recipient], output=output)
            manifest['artifacts'][alias] = {'file': path.name, 'sha256': sha256(path), 'bytes': path.stat().st_size,
                                           'startedAt': started, 'finishedAt': clock.now()}
        manifest['finishedAt'] = clock.now()
        validate_manifest(manifest)
        # The encrypted, authenticated manifest is the completion marker, written LAST.
        write_encrypted(bundle / 'manifest.json.age', json.dumps(manifest).encode(), recipient)
        print('COMPLETE encrypted set: ' + str(bundle), flush=True)
        print('Consistency: independent snapshots; distributed reconciliation is still required.', flush=True)
        return bundle
    except BaseException:
        # Retain only encrypted partial artifacts for diagnosis. No complete manifest => unusable set.
        (bundle / 'manifest.json.age').unlink(missing_ok=True)
        print('INCOMPLETE encrypted set retained: ' + str(bundle), file=sys.stderr)
        raise


class Restored:
    """Own only resources created here. No host ports, mounts or existing targets."""
    def __init__(self, bundle, identity):
        self.bundle = Path(bundle); self.identity = Path(identity)
        self.manifest = load_manifest(self.bundle, self.identity)
        self.run_id = 'minitech-recovery-' + secrets.token_hex(6)
        self.containers = []
        self.postgres = None; self.redis = None

    def container(self, image, *args, env=None):
        identifier = run('docker', 'run', '-d', '--network', 'none', '--label', 'minitech.recovery=' + self.run_id, *args, image, env=env)
        self.containers.append(identifier)
        return identifier

    def sql(self, alias, query):
        return run('docker', 'exec', self.postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', alias + '_db', '-Atqc', query)

    def redis_json(self, *args):
        return json.loads(run('docker', 'exec', self.redis, 'redis-cli', '-e', '--json', *map(str, args)))

    def __enter__(self):
        try:
            env = dict(os.environ, POSTGRES_PASSWORD=secrets.token_urlsafe(32))
            self.postgres = self.container(POSTGRES_IMAGE, '-e', 'POSTGRES_PASSWORD', env=env)
            for attempt in range(60):
                result = subprocess.run(['docker', 'exec', self.postgres, 'pg_isready', '-U', 'postgres'], capture_output=True, timeout=10)
                if result.returncode == 0:
                    test_query = subprocess.run(['docker', 'exec', self.postgres, 'psql', '-U', 'postgres', '-c', 'SELECT 1'], capture_output=True)
                    if test_query.returncode == 0: break
                time.sleep(.5)
            else: raise RuntimeError('Isolated PostgreSQL did not become ready')
            for alias in aliases():
                for retry in range(20):
                    res = subprocess.run(['docker', 'exec', self.postgres, 'createdb', '-U', 'postgres', '-T', 'template0', alias + '_db'], capture_output=True)
                    if res.returncode == 0: break
                    time.sleep(1)
                else:
                    raise RuntimeError('Command failed: createdb ' + alias + '_db: ' + res.stderr.decode().strip())
                stream(['age', '-d', '-i', str(self.identity), str(self.bundle / self.manifest['artifacts'][alias]['file'])],
                       ['docker', 'exec', '-i', self.postgres, 'pg_restore', '--exit-on-error', '--single-transaction', '--no-owner', '--no-privileges', '-U', 'postgres', '-d', alias + '_db'])
            self.redis = self.container_with_sleep()
            stream(['age', '-d', '-i', str(self.identity), str(self.bundle / 'redis.rdb.age')],
                   ['docker', 'exec', '-i', self.redis, 'sh', '-c', 'umask 077; cat > /data/dump.rdb'])
            run('docker', 'exec', self.redis, 'redis-check-rdb', '/data/dump.rdb')
            run('docker', 'exec', '-d', self.redis, 'redis-server', '--appendonly', 'no', '--save', '')
            for attempt in range(40):
                result = subprocess.run(['docker', 'exec', self.redis, 'redis-cli', 'PING'], capture_output=True, timeout=5)
                if result.returncode == 0 and result.stdout.strip() == b'PONG': break
                time.sleep(.25)
            else: raise RuntimeError('Restored Redis did not become ready')
            return self
        except BaseException:
            self.close(); raise

    def container_with_sleep(self):
        identifier = run('docker', 'run', '-d', '--network', 'none', '--label', 'minitech.recovery=' + self.run_id,
                         '--entrypoint', 'sh', REDIS_IMAGE, '-c', 'sleep 86400')
        self.containers.append(identifier)
        return identifier

    def close(self):
        failures = []
        for container in reversed(self.containers):
            try: run('docker', 'rm', '-f', '-v', container)
            except Exception: failures.append('container')
        self.containers.clear()
        if failures: raise RuntimeError('Recovery cleanup failed: ' + ', '.join(failures))

    def __exit__(self, *args): self.close()


def business_witness(restored, order_id):
    if type(order_id) is not int or order_id <= 0: raise ValueError('Order ID must be positive')
    raw = restored.sql('order', f"SELECT json_build_object('status',status,'invoiceRequested',invoice_requested,'reservationId',inventory_reservation_id,'total',total_price,'method',delivery_method) FROM orders WHERE id={order_id}")
    if not raw: return {'orderId': order_id, 'checks': {'orderExists': False}, 'passed': False}
    order = json.loads(raw, parse_float=Decimal)
    def exists(alias, sql): return restored.sql(alias, 'SELECT EXISTS (' + sql + ')') == 't'
    checks = {'paidOrder': order['status'] in ('PAID', 'SHIPPED', 'COMPLETED'),
        'successfulPayment': exists('payment', f"SELECT 1 FROM payments WHERE order_id={order_id} AND status='SUCCEEDED' AND amount={Decimal(order['total'])}"),
        'confirmedReservation': exists('inventory', f"SELECT 1 FROM inventory_reservations WHERE order_id={order_id} AND id={int(order['reservationId'] or 0)} AND status='CONFIRMED'"),
        'sentPaymentNotification': exists('notification', f"SELECT 1 FROM notifications WHERE source_id={order_id} AND event_type='PAYMENT_SUCCEEDED' AND status='SENT'")}
    if order['invoiceRequested']:
        checks['issuedInvoice'] = exists('billing', f"SELECT 1 FROM invoices WHERE order_id={order_id} AND status='ISSUED'")
    if order['method'] != 'STORE_PICKUP':
        checks['shipmentExists'] = exists('delivery', f'SELECT 1 FROM shipments WHERE order_id={order_id}')
    return {'orderId': order_id, 'checks': checks, 'passed': all(checks.values())}


def drill(bundle, identity, report_path, order_ids=()):
    started = time.monotonic()
    report = {'format': 1, 'status': 'FAILED', 'startedAt': now(), 'fullA36Accepted': False,
              'distributedCheckpoint': False, 'automaticReconciliationTested': False, 'releaseAllowed':False, 'privacyReconciled':False, 'privacyStatus':'CURRENT_INDEPENDENT_JOURNAL_REQUIRED'}
    report_path = Path(report_path)
    if report_path.is_symlink(): raise ValueError('Report path must not be a symlink')
    private_directory(report_path.parent)
    try:
        if len(order_ids) > 100 or any(type(identifier) is not int or identifier <= 0 for identifier in order_ids):
            raise ValueError('Provide at most 100 positive order IDs')
        target = Restored(bundle, identity)
        report['isolatedRunId'] = target.run_id
        with target as restored:
            report['bundle'] = restored.manifest['id']
            report['databaseTables'] = {alias: int(restored.sql(alias, "SELECT count(*) FROM pg_class WHERE relkind IN ('r','p') AND relnamespace='public'::regnamespace")) for alias in aliases()}
            if any(count == 0 for count in report['databaseTables'].values()): raise ValueError('Restored database has no application tables')
            if set(CORE_TABLES) != set(aliases()): raise ValueError('Restore schema inventory is stale')
            for alias, table in CORE_TABLES.items():
                if restored.sql(alias, "SELECT to_regclass('public." + table + "') IS NOT NULL") != 't':
                    raise ValueError('Missing expected application table for ' + alias)
            report['redisKeys'] = int(restored.redis_json('DBSIZE'))
            report['streams'] = {}
            for stream_name in ('payment-events', 'invoice-events'):
                if restored.redis_json('TYPE', stream_name) == 'stream':
                    groups = restored.redis_json('XINFO', 'GROUPS', stream_name)
                    groups = [group if isinstance(group, dict) else dict(zip(group[::2], group[1::2])) for group in groups]
                    report['streams'][stream_name] = {'entries': restored.redis_json('XLEN', stream_name), 'groups': len(groups), 'pending': sum(group['pending'] for group in groups)}
            report['witnesses'] = [business_witness(restored, identifier) for identifier in order_ids]
            report['businessVerified'] = bool(order_ids) and all(item['passed'] for item in report['witnesses'])
            report['status'] = 'PASS' if not order_ids or report['businessVerified'] else 'RECONCILIATION_REQUIRED'
        report['cleanupComplete'] = True
    except BaseException:
        report['status'] = 'FAILED'
        raise
    finally:
        report['durationSeconds'] = round(time.monotonic() - started, 3)
        report['finishedAt'] = now()
        with report_path.open('w') as output:
            report_path.chmod(0o600); json.dump(report, output, indent=2)
    print(report['status'] + ' isolated restore drill: ' + str(report_path), flush=True)
    return report


def prune(directory, identity, days, mode="observe"):
    if mode not in ("observe","purge"): raise ValueError("Invalid backup retention mode")
    if days < 1: raise ValueError('Retention days must be positive')
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir(): raise ValueError('Invalid checkpoint directory')
    removed = 0
    for bundle in sorted(directory.iterdir()):
        if bundle.is_symlink() or not bundle.is_dir() or not re.fullmatch(r'checkpoint-\d{8}T\d{6}Z-[0-9a-f]{8}', bundle.name): continue
        if not (bundle / 'manifest.json.age').exists(): continue
        manifest = load_manifest(bundle, identity, verify_hashes=False)
        if manifest['id'] != bundle.name: raise ValueError('Checkpoint directory/manifest identity mismatch')
        age_seconds = (datetime.now(timezone.utc) - datetime.fromisoformat(manifest['finishedAt'])).total_seconds()
        if age_seconds <= days * 86400: continue
        # Delete only authenticated, fixed artifact names; no recursive deletion.
        if mode=='observe':
            print('WOULD REMOVE complete encrypted set: '+bundle.name);removed+=1;continue
        for value in manifest['artifacts'].values(): (bundle / value['file']).unlink()
        (bundle / 'manifest.json.age').unlink(); bundle.rmdir(); removed += 1
    print(('Eligible complete encrypted sets: ' if mode=='observe' else 'Removed complete encrypted sets: ') + str(removed))
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    backup = commands.add_parser('capture'); backup.add_argument('--recipient', required=True); backup.add_argument('--output-dir', type=Path, required=True)
    restore = commands.add_parser('drill'); restore.add_argument('--bundle', type=Path, required=True); restore.add_argument('--identity', type=Path, required=True); restore.add_argument('--report', type=Path, required=True); restore.add_argument('--order-id', type=int, action='append', default=[])
    retention = commands.add_parser('prune'); retention.add_argument('--directory', type=Path, required=True); retention.add_argument('--identity', type=Path, required=True); retention.add_argument('--days', type=int, required=True); retention.add_argument('--mode',choices=['observe','purge'],default='observe')
    args = parser.parse_args()
    if args.command == 'capture': capture(json.load(sys.stdin), args.recipient, args.output_dir)
    elif args.command == 'drill':
        report = drill(args.bundle, args.identity, args.report, args.order_id)
        if report['status'] != 'PASS': return 2
    else: prune(args.directory, args.identity, args.days,args.mode)
    return 0


if __name__ == '__main__':
    try: sys.exit(main())
    except (ValueError, RuntimeError, KeyError, OSError, subprocess.SubprocessError) as error:
        print('Recovery operation failed: ' + type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)

from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from test_platform_credentials import load

recovery = load('platform_recovery', 'scripts/backup/platform-recovery.py')


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        stamp = datetime.now(timezone.utc) - timedelta(days=20)
        self.manifest = {'format': 1, 'id': 'checkpoint-20261001T010000Z-1234abcd',
                         'startedAt': stamp.isoformat(), 'finishedAt': (stamp + timedelta(minutes=1)).isoformat(),
                         'consistency': 'independent-snapshots', 'artifacts': {}}
        self.bundle = self.root / self.manifest['id']; self.bundle.mkdir()
        for name, filename in recovery.artifact_names().items():
            data = ('encrypted-placeholder-' + name).encode().ljust(64, b'x')
            (self.bundle / filename).write_bytes(data)
            self.manifest['artifacts'][name] = {'file': filename, 'sha256': hashlib.sha256(data).hexdigest(),
                'bytes': len(data), 'startedAt': stamp.isoformat(), 'finishedAt': stamp.isoformat()}
        (self.bundle / 'manifest.json.age').write_bytes(b'encrypted-manifest')

    def mocked_load(self, **kwargs):
        with patch.object(recovery, 'run', return_value=json.dumps(self.manifest)):
            return recovery.load_manifest(self.bundle, self.root / 'identity', **kwargs)

    def test_inventory_requires_14_databases_and_redis(self):
        self.assertEqual(14, len(recovery.aliases()))
        self.assertEqual(self.manifest, self.mocked_load())
        del self.manifest['artifacts']['redis']
        with self.assertRaisesRegex(ValueError, 'every database and Redis'): self.mocked_load()

    def test_path_traversal_and_unexpected_files_are_rejected(self):
        self.manifest['artifacts']['payment']['file'] = '../payment.dump.age'
        with self.assertRaisesRegex(ValueError, 'path'): self.mocked_load()
        self.manifest['artifacts']['payment']['file'] = 'payment.dump.age'
        (self.bundle / 'extra').write_text('do not touch')
        with self.assertRaisesRegex(ValueError, 'unexpected'): self.mocked_load()

    def test_missing_and_modified_artifacts_fail_before_docker(self):
        (self.bundle / 'payment.dump.age').write_bytes(b'z' * 64)
        with patch.object(recovery.subprocess, 'Popen') as process:
            with self.assertRaisesRegex(ValueError, 'checksum'): self.mocked_load()
            process.assert_not_called()
        (self.bundle / 'redis.rdb.age').unlink()
        with self.assertRaisesRegex(ValueError, 'Incomplete'): self.mocked_load()

    def test_symlink_is_rejected_even_if_bytes_match(self):
        path = self.bundle / 'redis.rdb.age'
        target = self.root / 'outside'; path.rename(target)
        try: path.symlink_to(target)
        except OSError: self.skipTest('Symlink creation unavailable')
        with self.assertRaisesRegex(ValueError, 'invalid artifact'): self.mocked_load()
        self.assertTrue(target.exists())

    def test_manifest_cannot_claim_a_distributed_checkpoint(self):
        self.manifest['consistency'] = 'atomic'
        with self.assertRaisesRegex(ValueError, 'consistency'): self.mocked_load()

    def test_invalid_capture_intervals_and_size_are_rejected(self):
        self.manifest['artifacts']['payment']['finishedAt'] = '2099-01-01T00:00:00+00:00'
        with self.assertRaisesRegex(ValueError, 'interval'): self.mocked_load()
        self.manifest['artifacts']['payment']['finishedAt'] = self.manifest['startedAt']
        self.manifest['artifacts']['payment']['bytes'] = True
        with self.assertRaisesRegex(ValueError, 'length'): self.mocked_load()

    def test_duplicate_json_keys_are_rejected(self):
        raw = json.dumps(self.manifest).replace('"format": 1', '"format": 1, "format": 1')
        with patch.object(recovery, 'run', return_value=raw):
            with self.assertRaisesRegex(ValueError, 'Duplicate'): recovery.load_manifest(self.bundle, 'identity')

    def test_both_sides_of_a_stream_must_succeed(self):
        good_reader = [sys.executable, '-c', 'import sys; sys.stdin.buffer.read()']
        bad_producer = [sys.executable, '-c', 'import sys; sys.exit(7)']
        with self.assertRaisesRegex(RuntimeError, 'source=7'): recovery.stream(bad_producer, good_reader)
        good_producer = [sys.executable, '-c', 'print("data")']
        bad_reader = [sys.executable, '-c', 'import sys; sys.stdin.buffer.read(); sys.exit(9)']
        with self.assertRaisesRegex(RuntimeError, 'destination=9'): recovery.stream(good_producer, bad_reader)

    def test_source_ids_and_database_names_cannot_inject_options(self):
        sources = {'databases': {alias: {'container': 'a' * 64, 'database': alias + '_db', 'user': 'audit'} for alias in recovery.aliases()}, 'redis': {'container': 'b' * 64}}
        recovery.validate_sources(sources)
        sources['databases']['order']['container'] = '--privileged'
        with self.assertRaises(ValueError): recovery.validate_sources(sources)
        sources['databases']['order']['container'] = 'a' * 64
        sources['databases']['order']['database'] = 'postgres'
        with self.assertRaises(ValueError): recovery.validate_sources(sources)

    def test_prune_deletes_a_whole_authenticated_set_only(self):
        with patch.object(recovery, 'run', return_value=json.dumps(self.manifest)):
            self.assertEqual(1, recovery.prune(self.root, 'identity', 14, mode='purge'))
        self.assertFalse(self.bundle.exists())

    def test_default_retention_only_previews_an_expired_set(self):
        with patch.object(recovery, 'run', return_value=json.dumps(self.manifest)):
            self.assertEqual(1, recovery.prune(self.root, 'identity', 14))
        self.assertTrue(self.bundle.exists())

    def test_prune_refuses_extra_files_and_preserves_recent_sets(self):
        (self.bundle / 'important').write_text('keep')
        with patch.object(recovery, 'run', return_value=json.dumps(self.manifest)):
            with self.assertRaises(ValueError): recovery.prune(self.root, 'identity', 14, mode='purge')
        self.assertTrue((self.bundle / 'payment.dump.age').exists())
        (self.bundle / 'important').unlink()
        self.manifest['startedAt'] = datetime.now(timezone.utc).isoformat()
        self.manifest['finishedAt'] = self.manifest['startedAt']
        for artifact in self.manifest['artifacts'].values():
            artifact['startedAt'] = self.manifest['startedAt']; artifact['finishedAt'] = self.manifest['startedAt']
        with patch.object(recovery, 'run', return_value=json.dumps(self.manifest)):
            self.assertEqual(0, recovery.prune(self.root, 'identity', 14, mode='purge'))
        self.assertTrue(self.bundle.exists())

    def test_report_does_not_mark_failed_restore_as_passed(self):
        report = self.root / 'report.json'
        with patch.object(recovery, 'Restored', side_effect=RuntimeError('fixture failure')):
            with self.assertRaises(RuntimeError): recovery.drill(self.bundle, 'identity', report)
        self.assertEqual('FAILED', json.loads(report.read_text())['status'])
        self.assertFalse(json.loads(report.read_text())['fullA36Accepted'])

    def test_legacy_dump_retention_cannot_break_platform_sets(self):
        profile = self.root / 'test'; checkpoint = profile / 'checkpoints' / 'fixture'; checkpoint.mkdir(parents=True)
        legacy = profile / 'order-db' / 'old.dump.age'; legacy.parent.mkdir(); legacy.write_bytes(b'old')
        grouped = checkpoint / 'order.dump.age'; grouped.write_bytes(b'keep')
        import os
        old = (datetime.now(timezone.utc) - timedelta(days=30)).timestamp()
        os.utime(legacy, (old, old)); os.utime(grouped, (old, old))
        script = '''source "$1"
BACKUP_RETENTION_MODE=purge; BACKUP_ROOT_DIRECTORY="$2"; PROJECT_ROOT=/unused/project; SECRET_ROOT_DIRECTORY=/unused/secrets
C_RED=; C_RESET=; active_profile() { printf test; }
db_backup_prune 14
'''
        result = subprocess.run(['bash', '-c', script, 'test', str(recovery.ROOT / 'scripts/control-center/lib/backups.sh'), str(self.root)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(legacy.exists())
        self.assertTrue(grouped.exists())

    def test_cc_capture_passes_complete_sources_without_database_passwords(self):
        script = '''source "$1"
PROJECT_ROOT="$2"; BACKUP_ROOT_DIRECTORY="$3"; SECRET_ROOT_DIRECTORY=/unused/secrets
DB_SERVICES=("${@:4}"); BROKER_SERVICE=event-broker; C_RED=; C_RESET=
active_profile() { printf test; }
secret_profile_supported() { [[ "$1" == test ]]; }
require_command() { return 0; }
backup_recipient_for_profile() { BACKUP_RECIPIENT=public-recipient; }
compose() { printf '%064d' 0; }
db_name() { printf '%s_db' "$1"; }
db_user() { printf '%s_app' "$1"; }
python3() {
  if [[ "$1" == */platform-recovery.py ]]; then
    command python3 -c 'import json,sys; json.dump({"sources":json.load(sys.stdin),"args":sys.argv[2:]},open(sys.argv[1],"w"))' "$BACKUP_ROOT_DIRECTORY/captured.json" "$@"
  else
    command python3 "$@"
  fi
}
platform_backup
'''
        result = subprocess.run(['bash', '-c', script, 'test', str(recovery.ROOT / 'scripts/control-center/lib/backups.sh'),
                                 str(recovery.ROOT), str(self.root), *[name + '-db' for name in recovery.aliases()]], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        captured = json.loads((self.root / 'captured.json').read_text())
        recovery.validate_sources(captured['sources'])
        self.assertIn('capture', captured['args'])
        self.assertIn('public-recipient', captured['args'])
        self.assertNotIn('password', json.dumps(captured).lower())


if __name__ == '__main__': unittest.main()

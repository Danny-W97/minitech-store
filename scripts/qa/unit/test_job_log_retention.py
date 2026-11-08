import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor

from test_platform_credentials import ROOT, server

logs = server.cc_job_logs


class ConfigurationTests(unittest.TestCase):
    def test_defaults_observe_and_invalid_limits_fail(self):
        value = logs.LogRetentionConfig.from_env({})
        self.assertEqual(('observe', 14, 100, 500, 600),
                         (value.mode, value.days, value.batch_size, value.scan_limit, value.interval_seconds))
        for name, value in [('MODE', 'delete'), ('DAYS', '0'), ('DAYS', '3651'), ('BATCH_SIZE', '1001'),
                            ('SCAN_LIMIT', '0'), ('SCAN_LIMIT', '10001'), ('INTERVAL_SECONDS', '0'), ('DAYS', 'bad')]:
            with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                logs.LogRetentionConfig.from_env({'CC_JOB_LOG_RETENTION_' + name: value})

    def test_unsupported_platform_does_not_touch_files_and_cannot_purge(self):
        with patch.object(logs, 'supported', return_value=False), patch.object(logs.os, 'open', side_effect=AssertionError('unexpected I/O')):
            worker = logs.JobLogRetention(ROOT / 'tmp/not-created', logs.LogRetentionConfig())
            worker.start(); worker.run_once(); worker.stop()
            self.assertFalse(worker.snapshot()['supported'])
            with self.assertRaises(ValueError): logs.JobLogRetention(ROOT / 'tmp/not-created', logs.LogRetentionConfig(mode='purge'))


@unittest.skipUnless(logs.supported(), 'POSIX/WSL locks required')
class JobLogRetentionTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='a43-6-', dir=ROOT / 'tmp')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'jobs'
        self.now, self.old = 2_000_000_000, 2_000_000_000 - 86460
        self.sequence = 0
        self.worker = self.make_worker()
        self.leases = []
        self.addCleanup(lambda: [lease.close() for lease in self.leases])

    def make_worker(self, **options):
        worker = logs.JobLogRetention(self.root, logs.LogRetentionConfig(mode=options.pop('mode', 'purge'), days=1, **options))
        self.addCleanup(worker.stop)
        return worker

    def run_cycle(self, worker=None):
        worker = worker or self.worker
        with patch.object(logs.time, 'time', return_value=self.now): worker.run_once()
        return worker.snapshot()

    def age(self, job_id, stamp=None):
        for suffix in ('.log', '.state'):
            path = self.root / (job_id + suffix)
            if path.exists(): os.utime(path, (stamp or self.old, stamp or self.old))

    def finished(self, retain=False, closed=None):
        self.sequence += 1
        job_id = f'{self.sequence:012x}'
        lease = self.worker.lease(job_id, self.root / (job_id + '.log'))
        self.leases.append(lease)
        with lease.open_log() as output: output.write('fixture job output\n')
        lease.mark_closed(self.old if closed is None else closed)
        self.age(job_id)
        if not retain: lease.retire()
        return job_id, lease

    def test_off_has_no_io_and_observe_never_deletes_or_reads_log_contents(self):
        job_id, _ = self.finished()
        inode = (self.root / (job_id + '.log')).stat().st_ino
        off = self.make_worker(mode='off')
        with patch.object(logs.os, 'open', side_effect=AssertionError('off accessed filesystem')):
            off.run_once(); self.assertIsNone(off.lease(job_id, self.root / (job_id + '.log')))
        observer = self.make_worker(mode='observe')
        original = os.read
        def metadata_only(fd, size):
            self.assertNotEqual(inode, os.fstat(fd).st_ino)
            self.assertLessEqual(size, 513)
            return original(fd, size)
        with patch.object(logs.os, 'read', side_effect=metadata_only): result = self.run_cycle(observer)
        self.assertEqual(1, result['lastRun']['eligibleFiles'])
        self.assertEqual(0, result['deletedTotal'])
        self.assertTrue((self.root / (job_id + '.log')).exists())

    def test_closed_log_and_marker_are_removed_and_accounted_for(self):
        job_id, _ = self.finished()
        size = (self.root / (job_id + '.log')).stat().st_size
        result = self.run_cycle()
        self.assertEqual((1, size, 1), (result['deletedTotal'], result['deletedBytesTotal'], result['removedMarkersTotal']))
        self.assertFalse((self.root / (job_id + '.log')).exists())
        self.assertFalse((self.root / (job_id + '.state')).exists())

    def test_retained_history_stays_locked_until_evicted(self):
        job_id, lease = self.finished(retain=True)
        self.assertEqual(1, self.run_cycle()['lastRun']['skippedActive'])
        lease.retire()
        self.assertEqual(1, self.run_cycle()['deletedTotal'])
        self.assertFalse((self.root / (job_id + '.log')).exists())

    def test_another_process_cannot_delete_this_processes_retained_history(self):
        job_id, lease = self.finished(retain=True)
        program = ('import json,sys; from cc_job_logs import *; '
                   'w=JobLogRetention(sys.argv[1],LogRetentionConfig(mode="purge",days=1)); '
                   'w.run_once(); print(json.dumps(w.snapshot())); w.stop()')
        result = subprocess.run([sys.executable, '-c', program, str(self.root)],
                                env={**os.environ, 'PYTHONPATH': str(ROOT / 'scripts/control-center/web')},
                                capture_output=True, text=True, timeout=10, check=True)
        self.assertEqual(1, json.loads(result.stdout)['lastRun']['skippedActive'])
        self.assertTrue((self.root / (job_id + '.log')).exists())
        lease.retire()

    def test_retiring_a_still_open_writer_does_not_release_its_lease_early(self):
        job_id = 'a' * 12
        lease = self.worker.lease(job_id, self.root / (job_id + '.log')); self.leases.append(lease)
        with lease.open_log() as output:
            output.write('fixture'); output.flush(); lease.retire(); self.age(job_id)
            self.assertEqual(1, self.run_cycle()['lastRun']['skippedActive'])
        lease.mark_closed(self.old); self.age(job_id)
        self.assertEqual(1, self.run_cycle()['deletedTotal'])

    def test_child_inheriting_stdout_holds_the_log_lock_after_parent_closes(self):
        job_id = 'b' * 12
        lease = self.worker.lease(job_id, self.root / (job_id + '.log')); self.leases.append(lease)
        with lease.open_log() as output:
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(20)'], stdout=output, stderr=subprocess.STDOUT)
        try:
            lease.mark_closed(self.old); self.age(job_id); lease.retire()
            self.assertEqual(1, self.run_cycle()['lastRun']['skippedActive'])
            self.assertTrue((self.root / (job_id + '.log')).exists())
        finally:
            child.terminate(); child.wait(timeout=5)
        self.assertEqual(1, self.run_cycle()['deletedTotal'])

    def test_unknown_legacy_and_crashed_unclosed_logs_are_protected(self):
        self.root.mkdir()
        legacy = self.root / ('c' * 12 + '.log'); legacy.write_text('legacy fixture')
        lease = self.worker.lease('d' * 12, self.root / ('d' * 12 + '.log')); self.leases.append(lease)
        with lease.open_log() as output: output.write('unclosed fixture')
        lease.close(); self.age('d' * 12)
        result = self.run_cycle()
        self.assertEqual(0, result['deletedTotal'])
        self.assertEqual(1, result['lastRun']['skippedUnknown'])
        self.assertEqual('legacy fixture', legacy.read_text())

    def test_all_three_age_boundaries_and_future_dates_are_protected(self):
        boundary = self.now - 86400
        self.finished(closed=boundary)
        log_boundary, _ = self.finished(); os.utime(self.root / (log_boundary + '.log'), (boundary, boundary))
        marker_boundary, _ = self.finished(); os.utime(self.root / (marker_boundary + '.state'), (boundary, boundary))
        self.finished(closed=self.now + 1)
        future_log, _ = self.finished(); os.utime(self.root / (future_log + '.log'), (self.now + 1, self.now + 1))
        self.assertEqual(0, self.run_cycle()['deletedTotal'])

    def test_malformed_metadata_never_certifies_a_log(self):
        values = [[], {'version': True, 'id': '', 'closedAt': self.old},
                  {'version': 1, 'id': 'wrong', 'closedAt': self.old},
                  {'version': 1, 'id': '', 'closedAt': float('inf')},
                  {'version': 1, 'id': '', 'closedAt': 10 ** 350}]
        for value in values:
            job_id, _ = self.finished()
            if isinstance(value, dict) and value['id'] == '': value['id'] = job_id
            (self.root / (job_id + '.state')).write_text(json.dumps(value))
            self.age(job_id)
        self.assertEqual(0, self.run_cycle()['deletedTotal'])
        self.assertEqual(len(values), self.worker.snapshot()['lastRun']['skippedUnknown'])

    def test_links_fifos_and_subdirectories_do_not_escape_the_job_namespace(self):
        outside = Path(self.temp.name) / 'outside'; outside.write_text('valuable fixture')
        symlink, _ = self.finished(); (self.root / (symlink + '.log')).unlink()
        (self.root / (symlink + '.log')).symlink_to(outside)
        hardlink, _ = self.finished(); os.link(self.root / (hardlink + '.log'), Path(self.temp.name) / 'log-link')
        state_link, _ = self.finished(); os.link(self.root / (state_link + '.state'), Path(self.temp.name) / 'state-link')
        (self.root / ('e' * 12 + '.state')).symlink_to(outside)
        (self.root / ('f' * 12 + '.state')).mkdir()
        result = self.run_cycle()
        self.assertEqual(0, result['deletedTotal']); self.assertEqual('valuable fixture', outside.read_text())

    def test_fifo_is_skipped_without_blocking_on_a_posix_filesystem(self):
        # DrvFS does not implement mkfifo; exercise that file type in an owned Linux temp directory.
        with tempfile.TemporaryDirectory(prefix='a43-6-fifo-') as directory:
            root = Path(directory).resolve() / 'jobs'
            worker = logs.JobLogRetention(root, logs.LogRetentionConfig(mode='purge', days=1))
            job_id = 'a' * 12
            lease = worker.lease(job_id, root / (job_id + '.log'))
            with lease.open_log() as output: output.write('fixture')
            lease.mark_closed(self.old); lease.retire()
            (root / (job_id + '.log')).unlink(); os.mkfifo(root / (job_id + '.log'))
            for suffix in ('.log', '.state'): os.utime(root / (job_id + suffix), (self.old, self.old))
            try:
                with patch.object(logs.time, 'time', return_value=self.now): worker.run_once()
                self.assertEqual(0, worker.snapshot()['deletedTotal'])
                self.assertEqual(1, worker.snapshot()['lastRun']['skippedUnknown'])
            finally:
                worker.stop()

    def test_symlinked_root_or_ancestor_is_rejected_without_touching_its_target(self):
        job_id, _ = self.finished()
        original = Path(self.temp.name) / 'original'; self.root.rename(original); self.root.symlink_to(original, target_is_directory=True)
        result = self.run_cycle()
        self.assertEqual(1, result['failures']); self.assertTrue((original / (job_id + '.log')).exists())
        self.assertNotIn(self.temp.name, json.dumps(result))
        with self.assertRaises(logs.LogSafetyError): self.worker.lease('e' * 12, self.root / ('e' * 12 + '.log'))

    def test_a_moved_directory_is_not_cleaned_through_an_old_scan_handle(self):
        worker = self.make_worker(scan_limit=1)
        job_id, _ = self.finished(); self.run_cycle(worker)
        original = Path(self.temp.name) / 'moved'; self.root.rename(original); self.root.mkdir()
        result = self.run_cycle(worker)
        self.assertEqual(1, result['failures']); self.assertTrue((original / (job_id + '.log')).exists())

    def test_scan_and_delete_budgets_resume_instead_of_starving_later_entries(self):
        for _ in range(12): self.finished()
        worker = self.make_worker(batch_size=1, scan_limit=3)
        for _ in range(100):
            result = self.run_cycle(worker)
            self.assertLessEqual(result['lastRun']['scannedEntries'], 3)
            self.assertLessEqual(result['lastRun']['deletedFiles'], 1)
            if result['deletedTotal'] == 12: break
        self.assertEqual(12, result['deletedTotal'])

    def test_parallel_collectors_do_not_double_count_deleted_files(self):
        for _ in range(8): self.finished()
        a, b = self.make_worker(batch_size=3), self.make_worker(batch_size=3)
        with patch.object(logs.time, 'time', return_value=self.now), ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda worker: worker.run_once(), [a, b]))
        for worker in (a, b): self.assertLessEqual(worker.snapshot()['deletedTotal'], 3)
        for _ in range(8): self.run_cycle(a); self.run_cycle(b)
        self.assertEqual(8, a.snapshot()['deletedTotal'] + b.snapshot()['deletedTotal'])

    def test_a_changed_log_is_rechecked_before_unlink(self):
        job_id, _ = self.finished(); original = os.stat
        def changed(path, *args, **kwargs):
            if path == job_id + '.log': os.utime(self.root / path, (self.now, self.now))
            return original(path, *args, **kwargs)
        with patch.object(logs.os, 'stat', side_effect=changed): result = self.run_cycle()
        self.assertEqual(0, result['deletedTotal']); self.assertEqual(1, result['lastRun']['skippedChanged'])

    def test_a_marker_failure_preserves_committed_deletion_counts_and_recovers(self):
        job_id, _ = self.finished(); original = os.unlink
        def deny_marker(path, *args, **kwargs):
            if path == job_id + '.state': raise PermissionError('fixture denial')
            return original(path, *args, **kwargs)
        with patch.object(logs.os, 'unlink', side_effect=deny_marker): result = self.run_cycle()
        self.assertEqual(1, result['deletedTotal']); self.assertEqual(1, result['failures']); self.assertIsNone(result['lastSuccessAt'])
        self.assertEqual('PermissionError', result['lastError'])
        result = self.run_cycle()
        self.assertEqual(1, result['deletedTotal']); self.assertEqual(1, result['removedMarkersTotal'])
        self.assertIsNone(result['lastError'])

    def test_uuid_collision_or_wrong_identity_does_not_overwrite_files(self):
        job_id, _ = self.finished()
        with self.assertRaises(logs.LogSafetyError): self.worker.lease(job_id, self.root / (job_id + '.log'))
        with self.assertRaises(logs.LogSafetyError): self.worker.lease('../escape', self.root / 'escape.log')
        with self.assertRaises(logs.LogSafetyError): self.worker.lease('e' * 12, Path(self.temp.name) / 'outside.log')
        self.assertEqual('fixture job output\n', (self.root / (job_id + '.log')).read_text())

    def test_real_runner_certifies_closed_output_and_releases_it_on_history_eviction(self):
        with patch.object(server, 'JOB_ROOT', self.root), patch.object(server, 'LOG_RETENTION', self.worker), \
                patch.object(server, 'MAX_JOB_HISTORY', 1), patch.dict(server.JOBS, {}, clear=True):
            server.SHUTTING_DOWN.clear()
            job = server.Job('a' * 12, '', 'Fixture', [sys.executable, '-c', 'print("runner fixture")'], self.root / ('a' * 12 + '.log'))
            server.register_job(job); self.leases.append(job.retention_lease); server.run_job(job, '')
            self.assertEqual('success', job.status)
            self.assertIsInstance(json.loads((self.root / ('a' * 12 + '.state')).read_text())['closedAt'], (float, int))
            job.retention_lease.mark_closed(self.old); self.age(job.id)
            self.assertEqual(0, self.run_cycle()['deletedTotal'])
            following = server.Job('b' * 12, '', 'Queued fixture', [], self.root / ('b' * 12 + '.log'))
            server.register_job(following); self.leases.append(following.retention_lease)
            self.assertEqual(1, self.run_cycle()['deletedTotal']); self.assertEqual({'b' * 12}, set(server.JOBS))

    def test_runner_timeout_certifies_only_after_terminating_and_closing_output(self):
        with patch.object(server, 'JOB_ROOT', self.root), patch.object(server, 'LOG_RETENTION', self.worker), \
                patch.object(server, 'JOB_TIMEOUT_SECONDS', .1), patch.dict(server.JOBS, {}, clear=True):
            server.SHUTTING_DOWN.clear()
            job = server.Job('a' * 12, '', 'Timeout fixture', [sys.executable, '-c', 'import time; time.sleep(20)'], self.root / ('a' * 12 + '.log'))
            server.register_job(job); self.leases.append(job.retention_lease); server.run_job(job, '')
            self.assertEqual('failed', job.status); self.assertIsNone(job.process)
            self.assertIsNotNone(json.loads((self.root / ('a' * 12 + '.state')).read_text())['closedAt'])

    def test_worker_lifecycle_has_no_import_startup_effects_and_stops_its_thread(self):
        self.worker.start()
        deadline = time.monotonic() + 3
        while not self.worker.snapshot()['runs'] and time.monotonic() < deadline: time.sleep(.01)
        self.worker.stop(); self.assertFalse(self.worker._thread.is_alive())
        self.assertIsNone(self.worker._directory_fd)

    def test_a_replaced_log_is_not_certified_as_the_original_writer_output(self):
        job_id = 'a' * 12
        lease = self.worker.lease(job_id, self.root / (job_id + '.log')); self.leases.append(lease)
        with lease.open_log() as output: output.write('original fixture')
        (self.root / (job_id + '.log')).rename(Path(self.temp.name) / 'original.log')
        (self.root / (job_id + '.log')).write_text('replacement fixture')
        lease.mark_closed(self.old); lease.retire(); self.age(job_id)
        self.assertEqual(0, self.run_cycle()['deletedTotal'])
        self.assertIsNone(json.loads((self.root / (job_id + '.state')).read_text())['closedAt'])

    def test_runner_fails_safely_if_the_root_changes_after_registration(self):
        # DrvFS itself refuses renaming a directory with a locked file. Exercise
        # the adversarial replacement on the Linux filesystem, where rename is legal.
        with tempfile.TemporaryDirectory(prefix='a43-6-root-') as directory:
            root = Path(directory).resolve() / 'jobs'
            worker = logs.JobLogRetention(root, logs.LogRetentionConfig(mode='purge'))
            with patch.object(server, 'JOB_ROOT', root), patch.object(server, 'LOG_RETENTION', worker), patch.dict(server.JOBS, {}, clear=True):
                server.SHUTTING_DOWN.clear()
                job = server.Job('a' * 12, '', 'Root fixture', ['unused'], root / ('a' * 12 + '.log'))
                server.register_job(job)
                try:
                    root.rename(Path(directory) / 'previous'); root.mkdir()
                    with patch.object(server.subprocess, 'Popen') as spawn: server.run_job(job, ''); spawn.assert_not_called()
                    self.assertEqual('failed', job.status); self.assertIsNone(job.process)
                    self.assertFalse(job.log_path.exists())
                finally:
                    job.retention_lease.close(); worker.stop()

    def test_a_log_inserted_after_registration_is_neither_overwritten_nor_certified(self):
        with patch.object(server, 'JOB_ROOT', self.root), patch.object(server, 'LOG_RETENTION', self.worker), patch.dict(server.JOBS, {}, clear=True):
            server.SHUTTING_DOWN.clear()
            job = server.Job('a' * 12, '', 'Collision fixture', ['unused'], self.root / ('a' * 12 + '.log'))
            server.register_job(job); self.leases.append(job.retention_lease)
            job.log_path.write_text('preserve fixture')
            with patch.object(server.subprocess, 'Popen') as spawn: server.run_job(job, ''); spawn.assert_not_called()
            self.assertEqual('failed', job.status); self.assertEqual('preserve fixture', job.log_path.read_text())
            self.assertIsNone(json.loads((self.root / ('a' * 12 + '.state')).read_text())['closedAt'])

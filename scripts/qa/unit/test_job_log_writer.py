import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from test_platform_credentials import ROOT, server, load

writers = server.cc_log_writer
sink = load('cc_log_sink', 'scripts/control-center/web/cc_log_sink.py')


class WriteConfigTests(unittest.TestCase):
    def test_defaults_and_invalid_limits(self):
        self.assertEqual(writers.WriteConfig(), writers.WriteConfig.from_env({}))
        for values in ({'CC_JOB_LOG_WRITE_MODE': 'bad'}, {'CC_JOB_LOG_WRITE_MAX_BYTES': '65535'},
                       {'CC_JOB_LOG_WRITE_MAX_BYTES': '16777217'}, {'CC_JOB_LOG_WRITE_MAX_BYTES': 'bad'}):
            with self.assertRaises(ValueError): writers.WriteConfig.from_env(values)

    def test_auto_is_direct_on_unsupported_platform_and_explicit_bounded_fails(self):
        with patch.object(writers, 'capture_supported', return_value=False):
            manager = writers.CaptureManager(ROOT / 'tmp/unused', writers.WriteConfig())
            self.assertFalse(manager.enabled); self.assertIsNone(manager.reserve())
            with self.assertRaises(ValueError): writers.CaptureManager(ROOT / 'tmp/unused', writers.WriteConfig(mode='bounded'))


@unittest.skipUnless(writers.capture_supported(), 'Linux/WSL detached sink')
class BoundedWriterTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='a43-8-', dir=ROOT / 'tmp')
        self.root = Path(self.temp.name) / 'jobs'
        self.writer = writers.CaptureManager(self.root, writers.WriteConfig(mode='bounded', max_bytes=65536))
        self.retention = server.cc_job_logs.JobLogRetention(self.root, server.cc_job_logs.LogRetentionConfig())
        self.jobs = []; self.reservations = []
        self.patches = [patch.object(server, 'JOB_ROOT', self.root), patch.object(server, 'LOG_WRITER', self.writer),
                        patch.object(server, 'LOG_RETENTION', self.retention), patch.dict(server.JOBS, {}, clear=True)]
        for item in self.patches: item.start()
        server.SHUTTING_DOWN.clear(); self.writer.start()

    def tearDown(self):
        for job in self.jobs:
            if job.process is not None: server.terminate_job(job.process)
            if job.log_capture is not None:
                job.log_capture.finish()
                try: job.log_capture.process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    job.log_capture.process.terminate(); job.log_capture.process.wait(timeout=3)
            if job.capture_reservation is not None: job.capture_reservation.close()
            if job.retention_lease is not None: job.retention_lease.close()
        for reservation in self.reservations: reservation.close()
        self.writer.stop(); self.retention.stop()
        for item in reversed(self.patches): item.stop()
        server.SHUTTING_DOWN.clear(); self.temp.cleanup()

    def job(self, code):
        job_id = f'{len(self.jobs)+1:012x}'
        job = server.Job(job_id, '', 'Writer fixture', [sys.executable, '-c', code], self.root / (job_id + '.log'))
        self.jobs.append(job); server.register_job(job)
        return job

    def done(self, job):
        job.log_capture.process.wait(timeout=5)
        value = job.log_capture.snapshot()
        self.assertTrue(value['complete'], value)
        self.assertIsNone(value['error'], value)
        return value

    def test_high_volume_stdout_is_bounded_and_preserves_header_and_tail(self):
        job = self.job("import os; os.write(1,b'FIRST\\n'); [os.write(1,b'x'*8192) for _ in range(80)]; os.write(1,b'\\nLATEST-FIXTURE')")
        server.run_job(job, ''); value = self.done(job)
        self.assertEqual('success', job.status); self.assertTrue(value['truncated']); self.assertGreater(value['droppedBytes'], 0)
        data = job.log_path.read_bytes()
        self.assertLessEqual(len(data), 65536); self.assertIn(b'MiniTech Control', data); self.assertIn(b'LATEST-FIXTURE', data)
        self.assertIn(b'earlier output omitted', data)
        public = job.public(include_output=True, lang='pl')
        self.assertTrue(public['outputWrite']['truncated']); self.assertIn('limit zapisu', public['output'])

    def test_stdin_stderr_utf8_and_exit_status_are_preserved(self):
        job = self.job("import sys; print(sys.stdin.read()); print('Błąd fixture',file=sys.stderr); sys.exit(7)")
        server.run_job(job, 'stdin fixture'); self.done(job)
        self.assertEqual(7, job.exit_code); self.assertEqual('failed', job.status)
        data = job.log_path.read_text(); self.assertIn('stdin fixture', data); self.assertIn('Błąd fixture', data)

    def test_timeout_kills_owned_command_and_preserves_the_footer(self):
        job = self.job('import time; time.sleep(20)')
        with patch.object(server, 'JOB_TIMEOUT_SECONDS', .1): server.run_job(job, '')
        self.done(job); self.assertEqual('failed', job.status)
        self.assertIn('Przekroczono limit czasu', job.log_path.read_text())

    def test_failed_spawn_closes_the_sink_and_certifies_only_its_own_log(self):
        job = self.job('unused'); job.command = ['/fixture/nonexistent-command']
        server.run_job(job, ''); self.done(job)
        self.assertEqual('failed', job.status); self.assertIn('FileNotFoundError', job.log_path.read_text())
        self.assertIsNotNone(json.loads(job.log_path.with_suffix('.state').read_text())['closedAt'])

    def test_slots_are_shared_by_managers_and_released_without_overwriting(self):
        other = writers.CaptureManager(self.root, writers.WriteConfig(mode='bounded'))
        for _ in range(8): self.reservations.append(self.writer.reserve())
        with self.assertRaises(writers.CaptureBusy): other.reserve()
        self.reservations.pop().close()
        self.reservations.append(other.reserve())
        self.assertEqual(8, len(self.reservations))

    def test_saturation_is_rejected_before_creating_a_log_or_running_a_command(self):
        for _ in range(8): self.reservations.append(self.writer.reserve())
        with self.assertRaises(server.ResourceLimitError): self.job('print("must not run")')
        self.assertEqual({}, server.JOBS); self.assertEqual([], list(self.root.glob('*.log')))

    def test_slot_links_are_rejected(self):
        self.root.mkdir(); outside = Path(self.temp.name) / 'outside'; outside.write_text('fixture')
        (self.root / '.capture-slot-0').symlink_to(outside)
        with self.assertRaises(OSError): self.writer.reserve()
        self.assertEqual('fixture', outside.read_text())

    def test_queued_shutdown_releases_reservation_and_never_spawns(self):
        job = self.job('print("must not run")'); server.shutdown_jobs()
        with patch.object(server.subprocess, 'Popen') as spawn: server.run_job(job, ''); spawn.assert_not_called()
        self.assertEqual('failed', job.status); self.assertIsNone(job.log_capture)

    def test_capture_operates_with_retention_off_and_keeps_closure_ownership(self):
        with patch.object(server, 'LOG_RETENTION', server.cc_job_logs.JobLogRetention(self.root, server.cc_job_logs.LogRetentionConfig(mode='off'))):
            job = self.job('print("fixture")')
        server.run_job(job, ''); self.done(job)
        self.assertIsNotNone(job.retention_lease)
        self.assertIsNotNone(json.loads(job.log_path.with_suffix('.state').read_text())['closedAt'])

    def test_short_output_is_not_truncated(self):
        job = self.job('print("short fixture")'); server.run_job(job, '')
        value = self.done(job); self.assertFalse(value['truncated']); self.assertEqual(0, value['droppedBytes'])

    def test_old_payload_is_not_retained_as_unbounded_memory(self):
        RollingLog = sink.RollingLog
        path = Path(self.temp.name) / 'ring.log'
        with path.open('w+b') as file:
            file.write(b'header\n'); file.flush()
            ring = RollingLog(file.fileno(), 65536, 7)
            for _ in range(200):
                ring.append(b'x'*8192)
                self.assertLessEqual(os.fstat(file.fileno()).st_size, 65536)
                self.assertLessEqual(ring.tail_size, 65536)
            self.assertTrue(ring.truncated)

    def test_disk_error_discards_output_but_keeps_draining_without_raising(self):
        RollingLog = sink.RollingLog
        path = Path(self.temp.name) / 'io.log'
        with path.open('w+b') as file:
            ring = RollingLog(file.fileno(), 65536, 0)
            with patch('cc_log_sink.write_all', side_effect=OSError('fixture disk full')): ring.append(b'fixture')
            ring.append(b'more fixture')
            self.assertEqual('OSError', ring.error); self.assertGreater(ring.dropped, 0)

    def test_expanded_header_is_also_bounded(self):
        job = self.job('print("fixture")'); job.title = 'x' * 10000
        server.run_job(job, ''); value = self.done(job)
        self.assertLessEqual(job.log_path.stat().st_size, 65536); self.assertTrue(value['truncated'])

    def test_truncated_write_is_a_sample_even_when_read_budget_contains_the_entire_file(self):
        job = self.job("import os,sys; [os.write(1,b'x'*8192) for _ in range(50)]; sys.exit(1)")
        server.run_job(job, ''); self.done(job)
        with patch.object(server, 'JOB_LOG_READ_MAX_BYTES', 1048576): value = job.public(include_output=True, lang='pl')
        self.assertFalse(value['outputReadLimited']); self.assertTrue(value['diagnosisSampled'])
        self.assertTrue(value['diagnosis']['sampled'])

    def test_descendant_keeps_logging_after_the_controller_execs_a_reload(self):
        root = Path(self.temp.name) / 'reloaded-jobs'
        controller = r'''
import json,os,sys,time,subprocess
from pathlib import Path
import server
root=Path(sys.argv[1]);root.mkdir()
manager=server.cc_log_writer.CaptureManager(root,server.cc_log_writer.WriteConfig(mode='bounded',max_bytes=65536))
retention=server.cc_job_logs.JobLogRetention(root,server.cc_job_logs.LogRetentionConfig())
server.JOB_ROOT=root;server.LOG_WRITER=manager;server.LOG_RETENTION=retention
background="import os,sys,time;from pathlib import Path;Path(sys.argv[1]).write_text(str(os.getpid()));deadline=time.monotonic()+8\nwhile not Path(sys.argv[2]).exists() and time.monotonic()<deadline:time.sleep(.02)\nfor i in range(100):os.write(1,b'z'*8192)\nos.write(1,b'AFTER_RELOAD_FIXTURE')"
business="import subprocess,sys;subprocess.Popen([sys.executable,'-c',"+repr(background)+","+repr(str(root/'writer.pid'))+","+repr(str(root/'release'))+"],start_new_session=True);print('PARENT_DONE')"
job=server.Job('a'*12,'','Reload fixture',[sys.executable,'-c',business],root/('a'*12+'.log'))
server.register_job(job);server.run_job(job,'')
deadline=time.monotonic()+3
while not (root/'writer.pid').exists() and time.monotonic()<deadline:time.sleep(.01)
print(json.dumps({'sink':job.log_capture.process.pid,'writer':int((root/'writer.pid').read_text()),'status':job.status}),flush=True)
sys.argv=['-c',"print('RELOAD_FIXTURE_OK')"]
server.reload_server()
'''
        result = subprocess.run([sys.executable, '-c', controller, str(root)],
            env={**os.environ, 'PYTHONPATH': str(ROOT / 'scripts/control-center/web')},
            capture_output=True, text=True, timeout=15, check=True)
        self.assertIn('RELOAD_FIXTURE_OK', result.stdout)
        value = json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))
        self.assertEqual('success', value['status'])
        sink_handle, writer_handle = writers.SinkHandle(value['sink']), writers.SinkHandle(value['writer'])
        other = writers.CaptureManager(root, writers.WriteConfig(mode='bounded'))
        reserved = []
        collector = server.cc_job_logs.JobLogRetention(root, server.cc_job_logs.LogRetentionConfig(mode='purge', days=1))
        try:
            self.assertIsNone(json.loads((root / ('a'*12+'.state')).read_text())['closedAt'])
            for _ in range(7): reserved.append(other.reserve())
            with self.assertRaises(writers.CaptureBusy): other.reserve()
            with patch.object(server.cc_job_logs.time, 'time', return_value=2_000_000_000): collector.run_once()
            self.assertEqual(1, collector.snapshot()['lastRun']['skippedActive'])
            self.assertEqual(0, collector.snapshot()['deletedTotal'])
            (root / 'release').touch()
            writer_handle.wait(timeout=5); sink_handle.wait(timeout=5)
            data = (root / ('a'*12+'.log')).read_bytes()
            self.assertLessEqual(len(data),65536);self.assertIn(b'AFTER_RELOAD_FIXTURE',data)
            self.assertIsNotNone(json.loads((root / ('a'*12+'.state')).read_text())['closedAt'])
            with patch.object(server.cc_job_logs.time, 'time', return_value=2_000_000_000): collector.run_once()
            self.assertEqual(1, collector.snapshot()['deletedTotal'])
        finally:
            (root / 'release').touch()
            writer_handle.terminate(); writer_handle.wait(timeout=5)
            sink_handle.terminate(); sink_handle.wait(timeout=5)
            for reservation in reserved:reservation.close()
            other.stop(); collector.stop()

    def test_forced_sink_exit_is_visible_and_releases_its_slot(self):
        import threading
        job = self.job("import os,time; time.sleep(.3); os.write(1,b'fixture')")
        thread = threading.Thread(target=server.run_job, args=(job, '')); thread.start()
        deadline = time.monotonic() + 3
        while job.log_capture is None and thread.is_alive() and time.monotonic() < deadline: time.sleep(.01)
        self.assertIsNotNone(job.log_capture)
        job.log_capture.process.terminate(); thread.join(timeout=5)
        self.assertFalse(thread.is_alive()); self.assertEqual('failed', job.status)
        self.assertEqual('SinkExited', job.log_capture.snapshot()['error'])
        self.assertIn('błąd zapisu', job.public(include_output=True, lang='pl')['output'])
        self.reservations.append(self.writer.reserve())

    def test_sink_does_not_inherit_task_environment_credentials(self):
        import threading
        release = Path(self.temp.name) / 'release-env'
        code = "import os,time;from pathlib import Path\nwhile not Path("+repr(str(release))+").exists():time.sleep(.01)\nprint(os.environ.get('CC_SENSITIVE_FIXTURE'))"
        job = self.job(code)
        with patch.dict(os.environ, {'CC_SENSITIVE_FIXTURE': 'dummy-fixture'}):
            thread = threading.Thread(target=server.run_job, args=(job, '')); thread.start()
            try:
                deadline = time.monotonic() + 3
                while job.log_capture is None and thread.is_alive() and time.monotonic() < deadline: time.sleep(.01)
                self.assertIsNotNone(job.log_capture)
                environment = Path('/proc') / str(job.log_capture.process.pid) / 'environ'
                self.assertNotIn(b'CC_SENSITIVE_FIXTURE', environment.read_bytes())
            finally:
                release.touch(); thread.join(timeout=5)
        self.assertFalse(thread.is_alive()); self.done(job)
        self.assertIn('dummy-fixture', job.log_path.read_text())

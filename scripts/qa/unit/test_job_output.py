from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from test_platform_credentials import ROOT, server

output = server.cc_job_output


class JobOutputTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='a43-7-', dir=ROOT / 'tmp')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'fixture.log'
        self.reader = output.JobOutputReader()
        self.diagnose = Mock(return_value={'matched': True, 'cause': {'pl': 'Fixture', 'en': 'Fixture'}})

    def inspect(self, **options):
        defaults = dict(limit=4096, failed=True, include_output=False, diagnose=self.diagnose, rules_version=(1,), lang='pl')
        defaults.update(options)
        return self.reader.inspect(self.path, **defaults)

    def test_default_and_invalid_read_limits(self):
        self.assertEqual(120000, output.read_limit_from_env({}))
        self.assertEqual(4096, output.read_limit_from_env({'CC_JOB_LOG_READ_MAX_BYTES': '4096'}))
        for value in ('0', '4095', '1048577', 'bad'):
            with self.assertRaises(ValueError): output.read_limit_from_env({'CC_JOB_LOG_READ_MAX_BYTES': value})

    @unittest.skipUnless(os.name == 'posix', 'Sparse fixture uses the Linux filesystem')
    def test_large_sparse_log_reads_at_most_the_budget_and_keeps_both_ends(self):
        with tempfile.TemporaryDirectory(prefix='a43-7-sparse-') as directory:
            self.path = Path(directory).resolve() / 'fixture.log'
            with self.path.open('wb') as file:
                file.write(b'HEAD-FIXTURE\n'); file.seek(1024 ** 3); file.write(b'\nTAIL-FIXTURE')
            original = os.read; total = [0]
            def counted(fd, count):
                data = original(fd, count); total[0] += len(data); return data
            with patch.object(output.os, 'read', side_effect=counted): result = self.inspect(include_output=True)
            self.assertLessEqual(total[0], 4096)
            self.assertIn('HEAD-FIXTURE', result['output']); self.assertIn('TAIL-FIXTURE', result['output'])
            self.assertIn('pominięto', result['output']); self.assertTrue(result['outputReadLimited'])
            self.assertTrue(result['diagnosisSampled']); self.assertLess(len(result['output']), 4300)

    def test_small_utf8_log_and_ansi_cleaning_keep_the_full_text(self):
        self.path.write_text('\x1b[31mZażółć fixture\x1b[0m\n', encoding='utf-8')
        result = self.inspect(include_output=True, clean=lambda value: server.ANSI_RE.sub('', value))
        self.assertEqual('Zażółć fixture\n', result['output'])
        self.assertFalse(result['outputReadLimited']); self.assertFalse(result['diagnosisSampled'])

    def test_unchanged_failed_summary_and_detail_reuse_the_diagnosis(self):
        self.path.write_text('fixture failure')
        self.inspect()
        with patch.object(output.os, 'read', side_effect=AssertionError('cache reread log')):
            self.inspect(); self.inspect()
        detail = self.inspect(include_output=True)
        self.assertEqual('fixture failure', detail['output']); self.diagnose.assert_called_once()

    def test_append_truncate_replacement_budget_and_rules_invalidate_the_cache(self):
        self.path.write_text('first'); self.inspect()
        with self.path.open('a') as file: file.write(' append')
        self.inspect(); self.path.write_text('short'); self.inspect()
        replacement = self.path.with_name('replacement.log'); replacement.write_text('replacement')
        os.replace(replacement, self.path); self.inspect()
        self.inspect(limit=8192); self.inspect(limit=8192, rules_version=(2,))
        self.assertEqual(6, self.diagnose.call_count)

    def test_none_diagnosis_is_also_cached(self):
        self.path.write_text('fixture'); self.diagnose.return_value = None
        self.assertIsNone(self.inspect()['diagnosis'])
        with patch.object(output.os, 'read', side_effect=AssertionError('negative cache reread')):
            self.assertIsNone(self.inspect()['diagnosis'])
        self.diagnose.assert_called_once()

    def test_concurrent_summaries_share_one_cache_miss(self):
        self.path.write_text('fixture failure')
        start = threading.Barrier(6)
        def read(_): start.wait(timeout=5); return self.inspect()
        with ThreadPoolExecutor(max_workers=6) as pool: list(pool.map(read, range(6)))
        self.diagnose.assert_called_once()

    def test_cache_does_not_retain_text_and_returned_nested_data_cannot_mutate_it(self):
        self.path.write_text('PRIVATE_FIXTURE_TEXT')
        result = self.inspect(); result['diagnosis']['cause']['pl'] = 'changed'
        self.assertEqual('Fixture', self.inspect()['diagnosis']['cause']['pl'])
        self.assertNotIn('PRIVATE_FIXTURE_TEXT', json.dumps(self.reader._cache_diagnosis))
        self.assertEqual({'_lock', '_cache_key', '_cache_diagnosis'}, set(vars(self.reader)))

    def test_changed_during_read_is_marked_and_not_cached(self):
        self.path.write_text('fixture'); original = output.read_at
        def changing(fd, offset, size):
            data = original(fd, offset, size)
            with self.path.open('a') as file: file.write(' append')
            return data
        with patch.object(output, 'read_at', side_effect=changing): result = self.inspect()
        self.assertTrue(result['outputReadChanged']); self.assertTrue(result['diagnosisSampled'])
        self.inspect(); self.inspect(); self.assertEqual(2, self.diagnose.call_count)

    def test_missing_and_io_failure_do_not_leak_paths_or_exception_messages(self):
        self.assertEqual('missing', self.inspect()['outputReadError'])
        with patch.object(output, 'open_log', side_effect=PermissionError('private fixture path')):
            result = self.inspect(include_output=True)
        self.assertEqual('unavailable', result['outputReadError'])
        self.assertNotIn('private fixture', json.dumps(result)); self.diagnose.assert_not_called()

    @unittest.skipUnless(os.name == 'posix', 'POSIX file types')
    def test_links_and_fifo_are_rejected_without_reading_their_targets(self):
        with tempfile.TemporaryDirectory(prefix='a43-7-types-') as directory:
            root = Path(directory).resolve(); target = root / 'valuable'; target.write_text('preserve fixture')
            self.path = root / 'link.log'; self.path.symlink_to(target)
            self.assertEqual('unavailable', self.inspect()['outputReadError'])
            self.path.unlink(); os.link(target, self.path)
            self.assertEqual('unavailable', self.inspect()['outputReadError'])
            self.path.unlink(); os.mkfifo(self.path)
            self.assertEqual('unavailable', self.inspect()['outputReadError'])
            self.assertEqual('preserve fixture', target.read_text()); self.diagnose.assert_not_called()

    def test_expected_owned_identity_rejects_a_replacement(self):
        self.path.write_text('fixture'); value = self.path.stat()
        identity = value.st_dev, value.st_ino
        replacement = self.path.with_name('other.log'); replacement.write_text('other fixture')
        os.replace(replacement, self.path)
        self.assertEqual('unavailable', self.inspect(expected_identity=identity)['outputReadError'])
        self.diagnose.assert_not_called()

    def test_bounded_diagnosis_note_is_localized_without_accumulating_in_cache(self):
        self.path.write_bytes(b'x' * 10000)
        a = self.inspect(); b = self.inspect(include_output=True, lang='en')
        self.assertEqual(1, a['diagnosis']['cause']['pl'].count('ograniczonej'))
        self.assertEqual(1, b['diagnosis']['cause']['en'].count('bounded'))
        self.assertIn('middle of log omitted', b['output']); self.diagnose.assert_called_once()

    def test_job_summaries_for_success_running_and_queued_never_open_the_log(self):
        for status in ('success', 'running', 'queued'):
            job = server.Job('fixture', '', 'Fixture', [], self.path, status=status)
            with patch.object(output, 'open_log', side_effect=AssertionError('summary opened log')):
                value = job.public(lang='pl')
            self.assertIsNone(value['diagnosis']); self.assertNotIn('output', value)
            self.assertIsNone(value['outputReadLimited'])

    def test_failed_job_summary_uses_bounded_reader_and_current_rule_version(self):
        self.path.write_bytes(b'head\n' + b'x' * 10000 + b'\ntail')
        job = server.Job('fixture', '', 'Fixture', [], self.path, status='failed')
        with patch.object(server, 'JOB_LOG_READ_MAX_BYTES', 4096), patch.object(server, 'diagnose_job_log', self.diagnose), \
                patch.object(output, 'file_version', return_value=(1,)):
            first = job.public(lang='pl'); job.public(lang='en')
            self.assertTrue(first['diagnosisSampled']); self.diagnose.assert_called_once()
        with patch.object(server, 'JOB_LOG_READ_MAX_BYTES', 4096), patch.object(server, 'diagnose_job_log', self.diagnose), \
                patch.object(output, 'file_version', return_value=(2,)):
            job.public(lang='pl')
        self.assertEqual(2, self.diagnose.call_count)

    def test_reserved_but_not_owned_file_is_not_read(self):
        self.path.write_text('injected fixture')
        job = server.Job('fixture', '', 'Fixture', [], self.path, status='failed')
        job.retention_lease = Mock(log_identity=None)
        with patch.object(output, 'open_log', side_effect=AssertionError('unowned file read')):
            self.assertEqual('', job.public(include_output=True, lang='pl')['output'])

    @unittest.skipUnless(server.cc_job_logs.supported(), 'POSIX writer lease')
    def test_owned_running_output_remains_readable_while_the_writer_holds_its_lock(self):
        root = Path(self.temp.name) / 'jobs'
        retention = server.cc_job_logs.JobLogRetention(root, server.cc_job_logs.LogRetentionConfig())
        path = root / ('a' * 12 + '.log'); lease = retention.lease('a' * 12, path)
        try:
            job = server.Job('a' * 12, '', 'Fixture', [], path, status='running', retention_lease=lease)
            with lease.open_log() as file:
                file.write('running fixture'); file.flush()
                self.assertEqual('running fixture', job.public(include_output=True, lang='pl')['output'])
        finally:
            lease.close(); retention.stop()

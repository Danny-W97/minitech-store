import http.client
from email.message import Message
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch
from test_platform_credentials import ROOT, server

security = sys.modules['panel_security']


def headers(*pairs):
    result = Message()
    for key, value in pairs: result[key] = value
    return result


class PolicyTests(unittest.TestCase):
    def setUp(self): self.policy = security.LocalRequestPolicy(18100)

    def test_local_authorities_and_matching_origins_work(self):
        for host in ['127.0.0.1:18100', 'localhost:18100', '[::1]:18100']:
            self.assertEqual(host, self.policy.validate(headers(('Host', host), ('Origin', 'http://' + host),
                             ('Sec-Fetch-Site', 'same-origin')), '/api/bootstrap', '127.0.0.1'))

    def test_rebinding_wrong_ports_and_ambiguous_hosts_are_rejected(self):
        for host in ['attacker.test:18100', 'localhost.attacker.test:18100', '127.0.0.1', 'localhost:18101',
                     'localhost.:18100', 'user@localhost:18100', 'localhost:18100/path', '127.1:18100',
                     '127.0.0.1:18100,localhost:18100', '[::1%lo]:18100', '[::1]evil:18100',
                     'localhost:18100#', 'localhost:18100?', 'localhost:18100:']:
            with self.subTest(host=host), self.assertRaises(security.PanelRejected):
                self.policy.validate(headers(('Host', host)), '/api/bootstrap', '127.0.0.1')
        for value in [headers(), headers(('Host', 'localhost:18100'), ('Host', 'localhost:18100'))]:
            with self.assertRaises(security.PanelRejected): self.policy.validate(value, '/', '127.0.0.1')

    def test_origin_fetch_metadata_and_proxy_style_targets_are_rejected(self):
        for origin in ['null', 'http://attacker.test:18100', 'http://127.0.0.1:18080', 'http://localhost:18100',
                       'https://127.0.0.1:18100', 'http://127.0.0.1:18100/', 'http://127.0.0.1:18100?q=x',
                       'http://127.0.0.1:18100?', 'http://127.0.0.1:18100#']:
            with self.subTest(origin=origin), self.assertRaises(security.PanelRejected):
                self.policy.validate(headers(('Host', '127.0.0.1:18100'), ('Origin', origin)), '/api/bootstrap', '127.0.0.1')
        for site in ['cross-site', 'same-site', 'invalid']:
            with self.assertRaises(security.PanelRejected):
                self.policy.validate(headers(('Host', 'localhost:18100'), ('Sec-Fetch-Site', site)), '/', '127.0.0.1')
        for target in ['http://attacker.test/api/bootstrap', '//localhost:18100/api/bootstrap']:
            with self.assertRaises(security.PanelRejected):
                self.policy.validate(headers(('Host', 'localhost:18100')), target, '127.0.0.1')
        with self.assertRaises(security.PanelRejected):
            self.policy.validate(headers(('Host', 'localhost:18100')), '/', '192.0.2.1')

    def test_session_tokens_are_independent_bound_and_not_stored_in_plaintext(self):
        store = security.PanelSessions()
        first, second = store.issue('localhost:18100'), store.issue('localhost:18100')
        self.assertNotEqual(first['token'], second['token'])
        self.assertNotIn(first['token'], store.sessions)
        store.authorize(first['token'], 'localhost:18100')
        with self.assertRaises(security.PanelRejected): store.authorize(first['token'], '127.0.0.1:18100')
        with self.assertRaises(security.PanelRejected): store.authorize('bad-token', 'localhost:18100')

    def test_idle_absolute_expiry_and_capacity_are_enforced(self):
        now = [0]
        store = security.PanelSessions(capacity=2, idle_seconds=5, lifetime_seconds=10, clock=lambda: now[0])
        first = store.issue('localhost:18100')['token']
        now[0] = 4; store.authorize(first, 'localhost:18100')
        now[0] = 8; store.authorize(first, 'localhost:18100')
        now[0] = 10
        with self.assertRaises(security.PanelRejected): store.authorize(first, 'localhost:18100')
        idle = store.issue('localhost:18100')['token']; now[0] = 15
        with self.assertRaises(security.PanelRejected): store.authorize(idle, 'localhost:18100')
        evicted = store.issue('localhost:18100')['token']
        store.issue('localhost:18100'); store.issue('localhost:18100')
        self.assertEqual(2, len(store.sessions))
        with self.assertRaises(security.PanelRejected): store.authorize(evicted, 'localhost:18100')


class PanelHttpTests(unittest.TestCase):
    def setUp(self):
        server.JOBS.clear()
        self.local = server.create_server('127.0.0.1', 0)
        self.thread = threading.Thread(target=self.local.serve_forever, daemon=True); self.thread.start()
        self.authority = f'127.0.0.1:{self.local.server_port}'
        self.profile = patch.object(server, 'active_profile', return_value='dev'); self.profile.start()
        self.language = patch.object(server, 'active_language', return_value='pl'); self.language.start()
        self.actions = patch.object(server, 'build_actions', return_value=[]); self.actions.start()

    def tearDown(self):
        self.local.shutdown(); self.local.server_close(); self.thread.join(timeout=3)
        self.profile.stop(); self.language.stop(); self.actions.stop(); server.JOBS.clear()

    def request(self, path='/api/bootstrap', method='GET', body=None, token=None, extra=(), host=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.local.server_port, timeout=5)
        data = None if body is None else json.dumps(body).encode()
        connection.putrequest(method, path, skip_host=True)
        connection.putheader('Host', host or self.authority)
        if token: connection.putheader('X-Control-Center-Token', token)
        for name, value in extra: connection.putheader(name, value)
        if data is not None:
            connection.putheader('Content-Type', 'application/json'); connection.putheader('Content-Length', str(len(data)))
        connection.endheaders(data)
        response = connection.getresponse(); content = response.read(); returned = dict(response.getheaders())
        connection.close()
        return response.status, json.loads(content) if content else {}, returned

    def test_bootstrap_issues_unique_tokens_with_no_cors_or_cache(self):
        status, first, response = self.request()
        self.assertEqual(200, status); self.assertEqual(43, len(first['token']))
        self.assertNotEqual(first['token'], self.request()[1]['token'])
        self.assertNotIn('Access-Control-Allow-Origin', response)
        self.assertIn('no-store', response['Cache-Control'])
        self.assertEqual('DENY', response['X-Frame-Options'])
        self.assertEqual(200, self.request('/api/jobs', token=first['token'])[0])

    def test_bad_host_origin_and_metadata_cannot_get_token_or_start_work(self):
        token = self.request()[1]['token']
        with patch.object(server, 'start_terminal') as terminal, patch.object(server, 'run_job') as job:
            variants = [dict(host=f'attacker.test:{self.local.server_port}'),
                        dict(extra=[('Origin', 'http://attacker.test')]),
                        dict(extra=[('Origin', f'http://localhost:{self.local.server_port}')]),
                        dict(extra=[('Sec-Fetch-Site', 'cross-site')])]
            for variant in variants:
                status, body, _ = self.request(**variant)
                self.assertEqual(403, status); self.assertNotIn('token', body)
                self.assertEqual(403, self.request('/api/terminal', 'POST', {}, token, **variant)[0])
                self.assertEqual(403, self.request('/api/jobs', 'POST', {}, token, **variant)[0])
            terminal.assert_not_called(); job.assert_not_called()

    def test_private_get_post_and_download_require_session_header(self):
        for path, method, body in [('/api/jobs', 'GET', None), ('/api/terminal/123', 'GET', None),
                ('/api/data/invoices/export-zip', 'GET', None), ('/api/jobs', 'POST', {}), ('/api/terminal', 'POST', {})]:
            self.assertEqual(401, self.request(path, method, body)[0])
        self.assertEqual(200, self.request('/api/ping')[0])

    def test_chat_history_requires_verified_operator_and_bounds_its_cursor(self):
        token=self.request()[1]['token']
        with patch.object(server,'api_json',return_value={'role':'CUSTOMER'}) as upstream:
            self.assertEqual(403,self.request('/api/chat/1/entries',token=token)[0])
            self.assertEqual(1,upstream.call_count)
        with patch.object(server,'api_json',side_effect=[{'role':'OPERATOR'},{'entries':[{'id':2,'message':'reply'}],'nextCursor':None,'hasMore':False}]) as upstream:
            status,body,_=self.request('/api/chat/1/entries?before=3&size=2',token=token)
            self.assertEqual(200,status);self.assertEqual('reply',body['entries'][0]['message'])
            self.assertTrue(upstream.call_args.args[0].endswith('/chat/messages/1/entries?before=3&size=2'))
        with patch.object(server,'api_json',return_value={'role':'OPERATOR'}) as upstream:
            self.assertEqual(400,self.request('/api/chat/1/entries?size=1000',token=token)[0])
            self.assertEqual(1,upstream.call_count)

    def test_duplicate_host_and_origin_absolute_target_and_expired_session_are_rejected(self):
        self.assertEqual(400, self.request(extra=[('Host', self.authority)])[0])
        self.assertEqual(403, self.request(extra=[('Origin', 'http://' + self.authority), ('Origin', 'http://' + self.authority)])[0])
        self.assertEqual(400, self.request('http://attacker.test/api/bootstrap')[0])
        self.assertEqual(403, self.request(extra=[('Sec-Fetch-Mode', 'navigate')])[0])
        token = self.request()[1]['token']
        self.local.panel_sessions.sessions.clear()
        self.assertEqual(401, self.request('/api/jobs', token=token)[0])

    def test_job_capacity_is_reserved_before_starting_another_thread(self):
        token = self.request()[1]['token']
        action = {'command': 'noop', 'title': 'Safe test', 'danger': False, 'fields': [], 'stdinFields': []}
        pending = threading.Event()
        def fake_run(job, stdin): pending.wait(timeout=3)
        try:
            with patch.object(server, 'MAX_RUNNING_JOBS', 1), patch.object(server, 'ACTION_BY_ID', {'safe': action}), \
                    patch.object(server, 'run_job', side_effect=fake_run) as run:
                self.assertEqual(202, self.request('/api/jobs', 'POST', {'action': 'safe'}, token)[0])
                self.assertEqual(429, self.request('/api/jobs', 'POST', {'action': 'safe'}, token)[0])
                self.assertEqual(1, run.call_count)
        finally: pending.set()


    @unittest.skipUnless(server.cc_log_writer.capture_supported(), 'Linux/WSL bounded sinks')
    def test_output_slot_capacity_rejects_http_work_before_starting_it(self):
        token = self.request()[1]['token']
        with tempfile.TemporaryDirectory(prefix='a43-8-http-', dir=ROOT / 'tmp') as directory:
            root = Path(directory) / 'jobs'
            manager = server.cc_log_writer.CaptureManager(root, server.cc_log_writer.WriteConfig(mode='bounded'))
            reservations = [manager.reserve() for _ in range(8)]
            action = {'command': 'noop', 'title': 'Fixture', 'danger': False, 'fields': [], 'stdinFields': []}
            try:
                with patch.object(server, 'JOB_ROOT', root), patch.object(server, 'LOG_WRITER', manager), \
                        patch.object(server, 'ACTION_BY_ID', {'safe': action}), patch.object(server, 'run_job') as run:
                    status, body, _ = self.request('/api/jobs', 'POST', {'action': 'safe'}, token)
                    self.assertEqual(429, status); self.assertEqual('CC_RESOURCE_LIMIT', body['code']); run.assert_not_called()
                    self.assertEqual({}, server.JOBS)
            finally:
                for reservation in reservations: reservation.close()
                manager.stop()

    def test_write_mode_status_is_visible_without_reserving_or_starting_a_sink(self):
        token = self.request()[1]['token']
        manager = server.cc_log_writer.CaptureManager(ROOT / 'tmp/unused-writer', server.cc_log_writer.WriteConfig(mode='direct'))
        with patch.object(server, 'LOG_WRITER', manager), patch.object(manager, 'reserve', side_effect=AssertionError('status mutated slots')):
            status, body, _ = self.request('/api/jobs/retention', token=token)
        self.assertEqual(200, status); self.assertEqual('direct', body['outputWriter']['effectiveMode'])
        self.assertIsNone(body['outputWriter']['limitBytes'])

    def test_success_job_list_does_not_open_output_files(self):
        token = self.request()[1]['token']
        server.JOBS['fixture'] = server.Job('fixture', '', 'Fixture', [], Path('never-opened'), status='success')
        with patch.object(server.cc_job_output, 'open_log', side_effect=AssertionError('summary opened log')):
            status, body, _ = self.request('/api/jobs', token=token)
        self.assertEqual(200, status); self.assertEqual('success', body['jobs'][0]['status'])
        self.assertNotIn('output', body['jobs'][0])

    def test_failed_job_details_are_bounded_and_summary_diagnosis_is_cached(self):
        token = self.request()[1]['token']
        with tempfile.TemporaryDirectory(prefix='a43-7-http-', dir=ROOT / 'tmp') as directory:
            path = Path(directory) / 'fixture.log'; path.write_text('HEAD-FIXTURE\n' + 'x' * 20000 + '\nTAIL-FIXTURE')
            server.JOBS['fixture'] = server.Job('fixture', '', 'Fixture', [], path, status='failed')
            diagnosis = Mock(return_value={'matched': False, 'cause': {'pl': 'Fixture', 'en': 'Fixture'}})
            with patch.object(server, 'JOB_LOG_READ_MAX_BYTES', 4096), patch.object(server, 'diagnose_job_log', diagnosis):
                status, first, _ = self.request('/api/jobs', token=token)
                self.assertEqual(200, status); self.assertTrue(first['jobs'][0]['diagnosisSampled'])
                self.assertNotIn('output', first['jobs'][0])
                status, detail, _ = self.request('/api/jobs/fixture', token=token)
                self.assertEqual(200, status); self.assertTrue(detail['outputReadLimited'])
                self.assertIn('HEAD-FIXTURE', detail['output']); self.assertIn('TAIL-FIXTURE', detail['output'])
                self.assertIn('pominięto', detail['output']); self.assertLess(len(detail['output']), 4300)
                with patch.object(server.cc_job_output.os, 'read', side_effect=AssertionError('cached summary reread')):
                    self.assertEqual(200, self.request('/api/jobs', token=token)[0])
                diagnosis.assert_called_once()

    def test_job_retention_status_requires_a_session_and_never_triggers_cleanup(self):
        token = self.request()[1]['token']
        retention = Mock()
        retention.snapshot.return_value = {'mode': 'observe', 'deletedTotal': 0}
        with patch.object(server, 'LOG_RETENTION', retention):
            self.assertEqual(401, self.request('/api/jobs/retention')[0])
            status, body, _ = self.request('/api/jobs/retention', token=token)
            self.assertEqual(200, status); self.assertEqual('observe', body['mode'])
            self.assertEqual(403, self.request('/api/jobs/retention', token=token, extra=[('Origin', 'http://attacker.test')])[0])
            self.assertEqual(404, self.request('/api/jobs/retention', 'POST', {}, token)[0])
            retention.snapshot.assert_called_once(); retention.run_once.assert_not_called()


class ResourceTests(unittest.TestCase):
    def tearDown(self):
        server.JOBS.clear()
        server.SHUTTING_DOWN.clear()

    def test_finished_job_history_is_bounded(self):
        with patch.object(server, 'MAX_JOB_HISTORY', 2):
            for index in range(4):
                job = server.Job(str(index), '', 'test', [], Path('unused'))
                job.status = 'success'; server.register_job(job)
            self.assertEqual(2, len(server.JOBS))
            self.assertEqual({'2', '3'}, set(server.JOBS))

    def test_job_timeout_terminates_its_owned_process(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            log = Path(directory) / 'job.log'
            job = server.Job('timeout', '', 'Timeout fixture', [sys.executable, '-c', 'import time; time.sleep(20)'], log)
            with patch.object(server, 'JOB_ROOT', Path(directory)), patch.object(server, 'JOB_TIMEOUT_SECONDS', .1):
                started = time.monotonic(); server.run_job(job, '')
            self.assertLess(time.monotonic() - started, 5)
            self.assertEqual('failed', job.status); self.assertIsNotNone(job.finished_at)
            self.assertIn('Przekroczono limit czasu', log.read_text(encoding='utf-8'))

    def test_shutdown_stops_owned_job_and_rejects_new_work(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            job = server.Job('shutdown', '', 'Shutdown fixture', [sys.executable, '-c', 'import time; time.sleep(20)'], Path(directory) / 'job.log')
            server.register_job(job)
            with patch.object(server, 'JOB_ROOT', Path(directory)):
                worker = threading.Thread(target=server.run_job, args=(job, ''))
                worker.start()
                deadline = time.monotonic() + 5
                while job.process is None and worker.is_alive() and time.monotonic() < deadline: time.sleep(.01)
                self.assertIsNotNone(job.process)
                process = job.process
                server.shutdown_jobs(); worker.join(timeout=5)
                self.assertFalse(worker.is_alive()); self.assertIsNotNone(process.poll())
                with self.assertRaises(server.ResourceLimitError): server.register_job(job)
                self.assertEqual('failed', job.status)

    def test_queued_job_does_not_start_after_shutdown(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            job = server.Job('queued', '', 'Queued fixture', ['unused'], Path(directory) / 'job.log')
            server.register_job(job); server.shutdown_jobs()
            with patch.object(server, 'JOB_ROOT', Path(directory)), patch.object(server.subprocess, 'Popen') as spawn:
                server.run_job(job, '')
                spawn.assert_not_called()
            self.assertEqual('failed', job.status)


@unittest.skipIf(server.pty is None, 'Real PTY requires Linux/WSL')
class TerminalTests(unittest.TestCase):
    def test_shutdown_bounds_a_terminal_that_ignores_term(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            script = Path(directory) / 'safe.sh'
            script.write_text('trap "" TERM HUP\necho SAFE_PTY_READY\nwhile :; do sleep 1; done\n', encoding='utf-8')
            with patch.object(server, 'CONTROL_SCRIPT', script):
                session = server.start_terminal()
                try:
                    deadline = time.monotonic() + 5
                    while b'SAFE_PTY_READY' not in session.output and time.monotonic() < deadline: time.sleep(.02)
                    self.assertIn(b'SAFE_PTY_READY', session.output)
                    started = time.monotonic(); server.shutdown_terminals()
                    self.assertLess(time.monotonic() - started, 5)
                    self.assertFalse(session.alive)
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        try: server.os.kill(session.pid, 0)
                        except ProcessLookupError: break
                        time.sleep(.02)
                    else: self.fail('Shutdown did not reap the PTY child')
                finally:
                    if session.alive: server.stop_terminal(session)
                    server.TERMINALS.clear()

    def test_real_pty_capacity_idle_and_absolute_lifetime(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            script = Path(directory) / 'safe.sh'
            script.write_text('echo SAFE_PTY_READY\nwhile read -r line; do echo "INPUT:$line"; done\n', encoding='utf-8')
            with patch.object(server, 'CONTROL_SCRIPT', script), patch.object(server, 'MAX_TERMINALS', 1):
                for expired_field, seconds in [('last_activity', server.TERMINAL_IDLE_SECONDS), ('created_at', server.TERMINAL_LIFETIME_SECONDS)]:
                    session = server.start_terminal()
                    try:
                        with self.assertRaises(server.ResourceLimitError): server.start_terminal()
                        deadline = time.monotonic() + 5
                        while b'SAFE_PTY_READY' not in session.output and time.monotonic() < deadline: time.sleep(.02)
                        self.assertIn(b'SAFE_PTY_READY', session.output)
                        with session.lock: setattr(session, expired_field, time.monotonic() - seconds - 1)
                        deadline = time.monotonic() + 5
                        while session.alive and time.monotonic() < deadline: time.sleep(.02)
                        self.assertFalse(session.alive)
                        deadline = time.monotonic() + 5
                        while time.monotonic() < deadline:
                            try: server.os.kill(session.pid, 0)
                            except ProcessLookupError: break
                            time.sleep(.02)
                        else: self.fail('PTY child was not reaped')
                    finally:
                        if session.alive: server.stop_terminal(session)
                server.TERMINALS.clear()


if __name__ == '__main__': unittest.main()

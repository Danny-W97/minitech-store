import concurrent.futures
import http.client
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from test_platform_credentials import ROOT, server

ai = sys.modules['ai_settings']
ENV_KEY = 'fixture-env-secret-that-must-not-be-persisted-1234'
OLD_KEY = 'fixture-legacy-secret-that-must-be-migrated-5678'
NEW_KEY = 'fixture-new-secret-that-must-be-encrypted-9012'


class SettingsFixture(unittest.TestCase):
    def setUp(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        # Native /tmp is needed to verify POSIX modes; WSL's /mnt/c can ignore chmod.
        self.directory = tempfile.TemporaryDirectory(prefix='ai-settings-', dir=None if os.name == 'posix' else ROOT / 'tmp')
        self.root = Path(self.directory.name)
        self.profile = 'dev'
        self.environ = {'PATH': os.environ.get('PATH', '')}
        self.config_file = self.root / 'runtime' / 'ai-config.json'
        self.vault_root = self.root / 'vault'
        self.settings = ai.AiSettings(self.config_file, lambda: self.vault_root, lambda: self.profile, self.environ)

    def tearDown(self): self.directory.cleanup()

    def legacy(self, key=OLD_KEY):
        self.config_file.parent.mkdir(parents=True, exist_ok=True)
        self.config_file.write_text(json.dumps(dict(ai.DEFAULTS, provider='codex', apiKey=key)), encoding='utf-8')


class AiSettingsTests(SettingsFixture):
    def test_environment_keys_are_runtime_only_even_after_model_and_provider_changes(self):
        self.environ.update(OPENAI_API_KEY=ENV_KEY, GEMINI_API_KEY=NEW_KEY)
        with patch.object(ai.subprocess, 'run') as process:
            first = self.settings.save({'provider': 'codex', 'model': 'chosen-model', 'apiKey': ''})
            self.assertEqual(ENV_KEY, first['apiKey']); self.assertEqual('environment', first['keySource'])
            self.assertEqual(ENV_KEY, self.settings.save({'includeContext': False})['apiKey'])
            self.assertEqual(NEW_KEY, self.settings.save({'provider': 'antigravity'})['apiKey'])
            process.assert_not_called()
        self.assertEqual(set(ai.DEFAULTS), set(json.loads(self.config_file.read_text(encoding='utf-8'))))
        for file in self.root.rglob('*'):
            if file.is_file():
                raw = file.read_text(encoding='utf-8')
                self.assertNotIn(ENV_KEY, raw); self.assertNotIn(NEW_KEY, raw)

    def test_public_configuration_contains_only_mask_and_source(self):
        self.environ['OPENAI_API_KEY'] = ENV_KEY
        public = ai.public_config(self.settings.save({'provider': 'codex'}))
        self.assertEqual('••••1234', public['maskedApiKey'])
        self.assertTrue(public['hasApiKey']); self.assertFalse(public['hasStoredApiKey'])
        self.assertNotIn('apiKey', public); self.assertNotIn(ENV_KEY, json.dumps(public))

    def test_empty_legacy_field_is_removed_without_requiring_sops(self):
        self.legacy('')
        with patch.object(ai.subprocess, 'run') as process: self.settings.load(); process.assert_not_called()
        self.assertNotIn('apiKey', json.loads(self.config_file.read_text(encoding='utf-8')))

    def test_missing_age_key_never_persists_a_new_credential(self):
        with self.assertRaises(ai.AiSettingsError): self.settings.save({'apiKey': NEW_KEY})
        self.assertFalse(self.config_file.exists())
        self.assertFalse(self.vault_root.exists())

    def test_failed_legacy_migration_preserves_original_and_does_not_expose_cli_output(self):
        self.legacy()
        key = self.vault_root / 'keys/dev.agekey'; key.parent.mkdir(parents=True); key.write_text('fixture', encoding='utf-8')
        with patch.object(ai.subprocess, 'run', return_value=Mock(returncode=1, stdout=OLD_KEY, stderr=OLD_KEY)):
            with self.assertRaises(ai.AiSettingsError) as failure: self.settings.load()
        self.assertNotIn(OLD_KEY, str(failure.exception))
        self.assertEqual(OLD_KEY, json.loads(self.config_file.read_text(encoding='utf-8'))['apiKey'])
        self.assertEqual([self.config_file], list(self.config_file.parent.iterdir()))
        if os.name == 'posix': self.assertEqual(0o600, stat.S_IMODE(self.config_file.stat().st_mode))

    def test_corrupt_preferences_are_not_silently_overwritten(self):
        self.config_file.parent.mkdir(parents=True); self.config_file.write_text('{broken', encoding='utf-8')
        with self.assertRaises(ai.AiSettingsError): self.settings.save({'model': 'other'})
        self.assertEqual('{broken', self.config_file.read_text(encoding='utf-8'))

    def test_invalid_preferences_and_credentials_have_safe_errors(self):
        for payload in [{'provider': []}, {'provider': '../dev'}, {'apiKey': None}, {'apiKey': 'bad\nkey'},
                        {'removeApiKey': 'yes'}, {'apiKey': NEW_KEY, 'removeApiKey': True},
                        {'customEndpoint': 'https://user:' + NEW_KEY + '@example.test/v1'},
                        {'customEndpoint': 'https://example.test/v1?key=' + NEW_KEY},
                        {'customEndpoint': 'https://example.test:bad/v1'}, {'customEndpoint': 'http://bad host/v1'},
                        {'includeContext': 'false'}, {'model': ['bad']}]:
            with self.subTest(fields=list(payload)), self.assertRaises(ai.AiSettingsError) as failure:
                self.settings.save(payload)
            self.assertNotIn(NEW_KEY, str(failure.exception))
        self.assertFalse(self.config_file.exists())

    def test_cli_failure_and_timeout_are_sanitized(self):
        for error in [OSError(NEW_KEY), subprocess.TimeoutExpired(['sops', NEW_KEY], 15, output=NEW_KEY)]:
            with patch.object(ai.subprocess, 'run', side_effect=error):
                with self.assertRaises(ai.AiSettingsError) as failure: self.settings._run(['sops'], Path('unused'))
                self.assertNotIn(NEW_KEY, str(failure.exception))

    def test_unverified_or_plaintext_sops_output_never_removes_legacy_key(self):
        self.legacy()
        key = self.vault_root / 'keys/dev.agekey'; key.parent.mkdir(parents=True); key.write_text('fixture', encoding='utf-8')
        variants = [
            ['age1fixture', json.dumps({'sops': {}, 'plaintext': OLD_KEY})],
            ['age1fixture', json.dumps({'sops': {}, 'payload': 'ENC[fixture]'}), json.dumps({'version': 1, 'keys': {'codex': NEW_KEY}})],
        ]
        for result in variants:
            with patch.object(self.settings, '_run', side_effect=result):
                with self.assertRaises(ai.AiSettingsError): self.settings.load()
            self.assertEqual(OLD_KEY, json.loads(self.config_file.read_text(encoding='utf-8'))['apiKey'])
            self.assertFalse((self.vault_root / 'secrets/ai-dev.json.sops').exists())

    def test_explicit_remove_never_unsets_an_environment_key(self):
        self.environ['OPENAI_API_KEY'] = ENV_KEY
        result = self.settings.save({'provider': 'codex', 'removeApiKey': True})
        self.assertEqual(ENV_KEY, result['apiKey']); self.assertFalse(result['hasStoredApiKey'])
        self.assertNotIn(ENV_KEY, self.config_file.read_text(encoding='utf-8'))

    def test_ai_headers_never_receive_operator_token_or_follow_redirects(self):
        with patch.object(server, 'operator_credential') as operator:
            for header in ['Authorization', 'x-goog-api-key']:
                request = server.ai_request('http://127.0.0.1:18080/v1/chat/completions', b'{}', {header: NEW_KEY})
                self.assertIn(NEW_KEY, request.unredirected_hdrs.values())
                redirected = server.urllib.request.HTTPRedirectHandler().redirect_request(request, None, 302, 'redirect', {}, 'http://other.example.test/')
                self.assertNotIn(NEW_KEY, redirected.headers.values())
            operator.assert_not_called()


TOOLS_AVAILABLE = bool(shutil.which('sops') and shutil.which('age-keygen'))


@unittest.skipUnless(TOOLS_AVAILABLE or os.environ.get('CC_REQUIRE_SOPS_TESTS') == '1', 'Real SOPS/age required')
class AiSopsTests(SettingsFixture):
    def setUp(self):
        super().setUp()
        self.assertTrue(TOOLS_AVAILABLE, 'CC_REQUIRE_SOPS_TESTS requires sops and age-keygen on PATH')
        (self.vault_root / 'keys').mkdir(parents=True)
        for profile in ['dev', 'test']:
            subprocess.run(['age-keygen', '-o', str(self.vault_root / 'keys' / f'{profile}.agekey')],
                           check=True, capture_output=True, text=True)

    def encrypted(self): return self.vault_root / 'secrets' / f'ai-{self.profile}.json.sops'

    def test_real_migration_is_verified_private_and_survives_restart(self):
        self.legacy()
        with patch.object(ai.subprocess, 'run', wraps=subprocess.run) as process:
            self.assertEqual(OLD_KEY, self.settings.load()['apiKey'])
            for call in process.call_args_list:
                self.assertNotIn(OLD_KEY, repr(call.args))  # Secret travels on stdin, never argv.
        self.assertNotIn('apiKey', json.loads(self.config_file.read_text(encoding='utf-8')))
        self.assertNotIn(OLD_KEY, self.encrypted().read_text(encoding='utf-8'))
        restarted = ai.AiSettings(self.config_file, lambda: self.vault_root, lambda: 'dev', self.environ)
        self.assertEqual(OLD_KEY, restarted.load()['apiKey'])
        self.assertEqual('sops', restarted.load()['keySource'])
        if os.name == 'posix':
            for file in [self.config_file, self.encrypted()]: self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))
        self.assertEqual([self.config_file], list(self.config_file.parent.iterdir()))

    def test_providers_and_profiles_do_not_share_keys(self):
        self.settings.save({'provider': 'codex', 'apiKey': OLD_KEY})
        self.settings.save({'provider': 'antigravity', 'apiKey': NEW_KEY})
        self.assertEqual(OLD_KEY, self.settings.save({'provider': 'codex', 'apiKey': ''})['apiKey'])
        self.profile = 'test'
        self.assertFalse(self.settings.load()['apiKey'])
        self.settings.save({'apiKey': ENV_KEY})
        self.profile = 'dev'
        self.assertEqual(OLD_KEY, self.settings.load()['apiKey'])
        self.assertEqual(NEW_KEY, self.settings.save({'provider': 'antigravity'})['apiKey'])

    def test_rotation_mask_empty_and_explicit_remove(self):
        self.environ['OPENAI_API_KEY'] = ENV_KEY
        self.settings.save({'provider': 'codex', 'apiKey': OLD_KEY})
        self.assertEqual(OLD_KEY, self.settings.save({'model': 'changed', 'apiKey': '••••5678'})['apiKey'])
        self.assertEqual(OLD_KEY, self.settings.save({'apiKey': ''})['apiKey'])
        self.assertEqual(NEW_KEY, self.settings.save({'apiKey': NEW_KEY})['apiKey'])
        result = self.settings.save({'removeApiKey': True})
        self.assertEqual(ENV_KEY, result['apiKey']); self.assertFalse(result['hasStoredApiKey'])
        for file in [self.config_file, self.encrypted()]:
            for key in [OLD_KEY, NEW_KEY, ENV_KEY]: self.assertNotIn(key, file.read_text(encoding='utf-8'))

    def test_interrupted_migration_can_be_retried_without_losing_either_copy(self):
        self.legacy()
        actual_write = ai.atomic_write
        def interrupted(path, value):
            if path == self.config_file: raise OSError('fixture interruption')
            actual_write(path, value)
        with patch.object(ai, 'atomic_write', side_effect=interrupted):
            with self.assertRaises(ai.AiSettingsError): self.settings.load()
        self.assertTrue(self.encrypted().is_file())
        self.assertEqual(OLD_KEY, json.loads(self.config_file.read_text(encoding='utf-8'))['apiKey'])
        self.assertEqual(OLD_KEY, self.settings.load()['apiKey'])
        self.assertNotIn('apiKey', json.loads(self.config_file.read_text(encoding='utf-8')))

    def test_corrupt_ciphertext_or_wrong_key_never_drops_existing_credentials(self):
        self.settings.save({'provider': 'codex', 'apiKey': OLD_KEY})
        settings_before = self.config_file.read_bytes()
        encrypted_before = self.encrypted().read_bytes()
        key = self.vault_root / 'keys/dev.agekey'
        key.write_bytes((self.vault_root / 'keys/test.agekey').read_bytes())
        with self.assertRaises(ai.AiSettingsError): self.settings.save({'apiKey': NEW_KEY})
        self.assertEqual(encrypted_before, self.encrypted().read_bytes())
        self.assertEqual(settings_before, self.config_file.read_bytes())
        self.encrypted().write_text('{invalid', encoding='utf-8')
        with self.assertRaises(ai.AiSettingsError): self.settings.load()
        self.assertEqual('{invalid', self.encrypted().read_text(encoding='utf-8'))

    def test_concurrent_provider_updates_preserve_all_keys(self):
        keys = dict(codex=OLD_KEY, antigravity=NEW_KEY, cursor=ENV_KEY)
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(lambda entry: self.settings.save({'provider': entry[0], 'apiKey': entry[1]}), keys.items()))
        for provider, key in keys.items(): self.assertEqual(key, self.settings.save({'provider': provider})['apiKey'])


class AiHttpTests(SettingsFixture):
    def setUp(self):
        super().setUp()
        self.environ['OPENAI_API_KEY'] = ENV_KEY
        self.settings.save({'provider': 'codex'})
        self.settings_patch = patch.object(server, 'AI_SETTINGS', self.settings); self.settings_patch.start()
        self.local = server.create_server('127.0.0.1', 0)
        self.worker = threading.Thread(target=self.local.serve_forever, daemon=True); self.worker.start()
        self.token = self.request('/api/bootstrap')[1]['token']

    def tearDown(self):
        self.local.shutdown(); self.local.server_close(); self.worker.join(timeout=3)
        self.settings_patch.stop(); super().tearDown()

    def request(self, path, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.local.server_port, timeout=5)
        headers = {'X-Control-Center-Token': getattr(self, 'token', ''), 'Content-Type': 'application/json'}
        connection.request('GET' if body is None else 'POST', path, None if body is None else json.dumps(body), headers)
        response = connection.getresponse(); value = json.loads(response.read()); connection.close()
        return response.status, value

    def test_get_and_model_save_return_only_mask_and_never_persist_env(self):
        for status, value in [self.request('/api/ai/config'), self.request('/api/ai/config', {'model': 'new'})]:
            self.assertEqual(200, status); self.assertEqual('••••1234', value['maskedApiKey'])
            self.assertNotIn(ENV_KEY, json.dumps(value)); self.assertNotIn('apiKey', value)
        self.assertNotIn(ENV_KEY, self.config_file.read_text(encoding='utf-8'))

    def test_failed_key_save_returns_safe_error_and_keeps_previous_config(self):
        before = self.config_file.read_bytes()
        status, value = self.request('/api/ai/config', {'apiKey': NEW_KEY})
        self.assertEqual(400, status); self.assertEqual('CC_AI_CONFIG_REJECTED', value['code'])
        self.assertNotIn(NEW_KEY, json.dumps(value)); self.assertEqual(before, self.config_file.read_bytes())

    def test_failed_legacy_migration_returns_503_without_disclosing_key(self):
        self.legacy()
        status, value = self.request('/api/ai/config')
        self.assertEqual(503, status); self.assertEqual('CC_AI_STORAGE_UNAVAILABLE', value['code'])
        self.assertNotIn(OLD_KEY, json.dumps(value))
        self.assertEqual(OLD_KEY, json.loads(self.config_file.read_text(encoding='utf-8'))['apiKey'])

    def test_ai_upstream_exception_cannot_echo_credentials(self):
        body = {'message': 'hello'}
        body['reviewToken'] = self.request('/api/ai/chat/preview', body)[1]['reviewToken']
        with patch.object(server, 'open_ai_request', side_effect=RuntimeError(ENV_KEY)), \
                patch.object(server, 'generate_local_knowledge_reply', return_value='offline'):
            status, value = self.request('/api/ai/chat', body)
        self.assertEqual(200, status); self.assertEqual('AI_UPSTREAM_UNAVAILABLE', value['error'])
        self.assertNotIn(ENV_KEY, json.dumps(value))

    def test_gemini_key_is_sent_as_header_and_not_in_url(self):
        self.environ['GEMINI_API_KEY'] = NEW_KEY
        self.settings.save({'provider': 'antigravity'})
        response = Mock()
        response.__enter__ = Mock(return_value=response); response.__exit__ = Mock(return_value=False)
        response.read.return_value = b'{"candidates":[{"content":{"parts":[{"text":"fixture response"}]}}]}'
        body = {'message': 'hello'}
        body['reviewToken'] = self.request('/api/ai/chat/preview', body)[1]['reviewToken']
        with patch.object(server, 'open_ai_request', return_value=response) as upstream:
            status, value = self.request('/api/ai/chat', body)
        self.assertEqual(200, status); self.assertEqual('fixture response', value['reply'])
        request = upstream.call_args.args[0]
        self.assertEqual(NEW_KEY, request.get_header('X-goog-api-key'))
        self.assertNotIn(NEW_KEY, request.full_url); self.assertNotIn('?key=', request.full_url)


if __name__ == '__main__': unittest.main()

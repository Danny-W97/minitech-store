"""AI preferences without plaintext credentials; profile-scoped SOPS storage."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
from urllib.parse import urlsplit


DEFAULTS = {
    'provider': 'antigravity',
    'model': 'antigravity-expert',
    'customEndpoint': 'http://localhost:11434/v1',
    'includeContext': True,
}
PROVIDERS = {'antigravity', 'codex', 'cursor'}
ENV_KEYS = {
    'antigravity': ('GEMINI_API_KEY', 'GOOGLE_API_KEY', 'ANTIGRAVITY_API_KEY'),
    'codex': ('OPENAI_API_KEY', 'CODEX_API_KEY'),
    'cursor': (),
}


class AiSettingsError(Exception):
    """A deliberately credential-free message suitable for the local HTTP API."""


def private_file(path):
    if path.is_symlink(): raise AiSettingsError('Plik konfiguracji AI nie może być dowiązaniem symbolicznym.')
    if os.name == 'posix': path.chmod(0o600)


def atomic_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink(): raise AiSettingsError('Plik konfiguracji AI nie może być dowiązaniem symbolicznym.')
    descriptor, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def preferences(value):
    result = {key: value.get(key, default) for key, default in DEFAULTS.items()}
    if result['provider'] == 'cursor_custom': result['provider'] = 'cursor'
    if not isinstance(result['provider'], str) or result['provider'] not in PROVIDERS: raise AiSettingsError('Nieobsługiwany dostawca AI.')
    for field, limit in [('model', 256), ('customEndpoint', 2048)]:
        text = result[field]
        if not isinstance(text, str) or len(text) > limit or any(ord(char) < 32 for char in text):
            raise AiSettingsError('Nieprawidłowe ustawienia połączenia AI.')
        result[field] = text.strip()
    if not isinstance(result['includeContext'], bool): raise AiSettingsError('Nieprawidłowe ustawienie kontekstu AI.')
    try:
        endpoint = urlsplit(result['customEndpoint'])
        if endpoint.scheme not in {'http', 'https'} or not endpoint.hostname or any(char.isspace() for char in result['customEndpoint']):
            raise ValueError()
        if endpoint.port is None and endpoint.netloc.endswith(':'):
            raise ValueError()
        if endpoint.username is not None or endpoint.password is not None or '?' in result['customEndpoint'] or '#' in result['customEndpoint']:
            raise ValueError()
    except ValueError:
        raise AiSettingsError('Endpoint AI musi być adresem HTTP(S) bez hasła, parametrów i fragmentu. Klucz wpisz w osobnym polu.') from None
    return result


def credential(value):
    if not isinstance(value, str) or len(value) > 8192 or any(ord(char) < 32 for char in value):
        raise AiSettingsError('Nieprawidłowy klucz AI.')
    return value.strip()


class AiSettings:
    def __init__(self, settings_file, secret_root, profile, environ=None):
        self.settings_file = Path(settings_file)
        self.secret_root, self.profile = secret_root, profile
        self.environ = os.environ if environ is None else environ
        self.lock = threading.RLock()

    def _context(self):
        profile = self.profile()
        if profile not in {'dev', 'test'}: raise AiSettingsError('Nieobsługiwany profil sekretów AI.')
        root = Path(self.secret_root())
        return profile, root / 'keys' / f'{profile}.agekey', root / 'secrets' / f'ai-{profile}.json.sops'

    def _run(self, args, key_file, data=None):
        env = dict(self.environ, SOPS_AGE_KEY_FILE=str(key_file))
        env.pop('SOPS_AGE_KEY', None)
        env.pop('SOPS_AGE_KEY_CMD', None)
        try:
            process = subprocess.run(args, input=data, capture_output=True, text=True, encoding='utf-8',
                                     env=env, timeout=15, check=False)
        except (OSError, subprocess.SubprocessError):
            raise AiSettingsError('Magazyn AI jest niedostępny. Sprawdź SOPS, age-keygen i klucz age aktywnego profilu.') from None
        if process.returncode != 0:
            # CLI stderr, stdout and exception arguments can contain credentials.
            raise AiSettingsError('Nie udało się obsłużyć magazynu AI. Sprawdź SOPS i klucz age aktywnego profilu.')
        return process.stdout

    def _key_ready(self, key_file):
        if not key_file.is_file() or key_file.is_symlink():
            raise AiSettingsError('Brak klucza age profilu. Utwórz sekrety DEV/TEST przed zapisaniem klucza AI.')
        private_file(key_file)

    def _decode(self, text):
        try:
            value = json.loads(text)
            if not isinstance(value, dict) or value.get('version') != 1 or not isinstance(value.get('keys'), dict): raise ValueError()
            keys = value['keys']
            if not all(provider in PROVIDERS and credential(key) == key and bool(key) for provider, key in keys.items()): raise ValueError()
            return keys
        except (ValueError, TypeError, AiSettingsError):
            raise AiSettingsError('Nieprawidłowy magazyn kluczy AI; dotychczasowe pliki zachowano.') from None

    def _read_keys(self, key_file, encrypted):
        if not encrypted.exists(): return {}
        private_file(encrypted)
        self._key_ready(key_file)
        raw = self._run(['sops', 'decrypt', '--input-type', 'json', '--output-type', 'json', str(encrypted)], key_file)
        return self._decode(raw)

    def _write_keys(self, key_file, encrypted, keys):
        self._key_ready(key_file)
        recipient = self._run(['age-keygen', '-y', str(key_file)], key_file).strip()
        if not re.fullmatch(r'age1[0-9a-z]+', recipient): raise AiSettingsError('Nieprawidłowy publiczny klucz age profilu.')
        raw = json.dumps({'version': 1, 'keys': keys}, ensure_ascii=False)
        ciphertext = self._run(['sops', 'encrypt', '--input-type', 'json', '--output-type', 'json',
                                '--filename-override', str(encrypted), '--age', recipient], key_file, raw)
        try:
            document = json.loads(ciphertext)
            if not isinstance(document.get('sops'), dict): raise ValueError()
            if any(key in ciphertext for key in keys.values()): raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise AiSettingsError('SOPS nie zwrócił zaszyfrowanego magazynu AI; dotychczasowe pliki zachowano.') from None
        # Verify the ciphertext with this profile's key before replacing any file.
        verified = self._run(['sops', 'decrypt', '--input-type', 'json', '--output-type', 'json',
                              '--filename-override', str(encrypted)], key_file, ciphertext)
        if self._decode(verified) != keys: raise AiSettingsError('Weryfikacja szyfrowania klucza AI nie powiodła się.')
        atomic_write(encrypted, ciphertext)

    def _read_preferences(self, key_file, encrypted):
        if not self.settings_file.exists(): return dict(DEFAULTS)
        private_file(self.settings_file)
        try:
            saved = json.loads(self.settings_file.read_text(encoding='utf-8'))
            if not isinstance(saved, dict): raise ValueError()
        except (ValueError, OSError):
            raise AiSettingsError('Nie można odczytać konfiguracji AI; dotychczasowy plik zachowano.') from None
        config = preferences(saved)
        if 'apiKey' in saved:
            legacy = credential(saved['apiKey'])
            if legacy:
                keys = self._read_keys(key_file, encrypted)
                existing = keys.get(config['provider'])
                if existing is not None and existing != legacy:
                    raise AiSettingsError('Konflikt starego klucza AI z magazynem SOPS. Zachowano oba pliki; wymagana ręczna migracja.')
                keys[config['provider']] = legacy
                self._write_keys(key_file, encrypted, keys)
            # Empty legacy fields are also removed. Never create a plaintext backup.
            atomic_write(self.settings_file, json.dumps(config, indent=2, ensure_ascii=False))
        return config

    def _runtime(self, config, profile, key_file, encrypted):
        stored = self._read_keys(key_file, encrypted).get(config['provider'], '')
        key = stored
        source = 'sops' if stored else 'none'
        if not key:
            for name in ENV_KEYS[config['provider']]:
                candidate = credential(self.environ.get(name, ''))
                if candidate:
                    key, source = candidate, 'environment'
                    break
        return dict(config, apiKey=key, keySource=source, hasStoredApiKey=bool(stored), credentialProfile=profile)

    def load(self):
        with self.lock:
            try:
                profile, key_file, encrypted = self._context()
                return self._runtime(self._read_preferences(key_file, encrypted), profile, key_file, encrypted)
            except OSError:
                raise AiSettingsError('Nie można bezpiecznie odczytać ustawień AI; sprawdź uprawnienia plików.') from None

    def save(self, payload):
        with self.lock:
            try:
                profile, key_file, encrypted = self._context()
                current = self._read_preferences(key_file, encrypted)
                config = preferences(dict(current, **{key: value for key, value in payload.items() if key in DEFAULTS}))
                new_key = credential(payload.get('apiKey', ''))
                remove = payload.get('removeApiKey', False)
                if not isinstance(remove, bool): raise AiSettingsError('Nieprawidłowe pole usunięcia klucza AI.')
                if new_key.startswith('••••'): new_key = ''  # Older open tabs can still submit a mask.
                if remove and new_key: raise AiSettingsError('Wybierz zapis nowego klucza albo usunięcie zapisanego klucza.')
                if new_key or remove:
                    keys = self._read_keys(key_file, encrypted)
                    if new_key: keys[config['provider']] = new_key
                    else: keys.pop(config['provider'], None)
                    if keys or encrypted.exists(): self._write_keys(key_file, encrypted, keys)
                runtime = self._runtime(config, profile, key_file, encrypted)
                atomic_write(self.settings_file, json.dumps(config, indent=2, ensure_ascii=False))
                return runtime
            except OSError:
                raise AiSettingsError('Nie można bezpiecznie zapisać ustawień AI; sprawdź uprawnienia plików.') from None


def public_config(config):
    key = config['apiKey']
    return {**{name: config[name] for name in DEFAULTS},
            'hasApiKey': bool(key), 'maskedApiKey': '••••' + key[-4:] if len(key) >= 8 else ('••••' if key else ''),
            'keySource': config['keySource'], 'hasStoredApiKey': config['hasStoredApiKey'],
            'credentialProfile': config['credentialProfile']}

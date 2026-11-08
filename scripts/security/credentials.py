#!/usr/bin/env python3
"""Create missing per-service credentials in a private dotenv file; never print secrets."""
import argparse
import base64
import hashlib
import json
import re
import secrets
import shlex
from pathlib import Path

SERVICES = tuple(sorted(path.name for path in Path(__file__).resolve().parents[2].glob('*-service') if path.is_dir()))


def read_dotenv(content):
    values = {}
    for line in content.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = shlex.split(line, comments=True)
        if len(parts) == 1 and '=' in parts[0]:
            key, value = parts[0].split('=', 1)
            values[key] = value
    return values


def mfa_key(value):
    try:
        if len(base64.b64decode(value, validate=True)) != 32:
            raise ValueError()
    except Exception:
        raise ValueError('MFA keys must contain exactly 32 Base64-encoded bytes') from None
    return value


def ensure_mfa(values, rotate=False):
    updates = {}
    version = values.get('CUSTOMER_MFA_KEY_FORMAT')
    if version not in (None, '', 'v1'):
        raise ValueError('Unknown MFA key configuration format')
    if version != 'v1':
        # Preserve the exact OLD Java derivation before JWT or direct MFA material can change.
        material = next((values.get(name) for name in ('CUSTOMER_MFA_ENCRYPTION_KEY_BASE64',
                        'CUSTOMER_JWT_PRIVATE_KEY_BASE64', 'CUSTOMER_JWT_SECRET')
                        if isinstance(values.get(name), str) and values[name].strip()), None)
        if material and not values.get('CUSTOMER_MFA_LEGACY_KEY_BASE64'):
            updates['CUSTOMER_MFA_LEGACY_KEY_BASE64'] = base64.b64encode(hashlib.sha256(material.encode()).digest()).decode()
        updates['CUSTOMER_MFA_KEY_FORMAT'] = 'v1'
    if values.get('CUSTOMER_MFA_LEGACY_KEY_BASE64'):
        mfa_key(values['CUSTOMER_MFA_LEGACY_KEY_BASE64'])
    active = values.get('CUSTOMER_MFA_ENCRYPTION_KEY_BASE64')
    if version == 'v1' and not active:
        raise ValueError('Configured MFA key is missing; restore it instead of generating a replacement')
    if active:
        try:
            mfa_key(active)
        except ValueError:
            if version == 'v1':
                raise
            active = None  # Its old derived key was preserved above.
    if not active:
        active = base64.b64encode(secrets.token_bytes(32)).decode()
        updates['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'] = active
    kid = values.get('CUSTOMER_MFA_ACTIVE_KID') or 'mfa-v1'
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', kid):
        raise ValueError('Invalid active MFA key id')
    if not values.get('CUSTOMER_MFA_ACTIVE_KID'):
        updates['CUSTOMER_MFA_ACTIVE_KID'] = kid
    previous = json.loads(values.get('CUSTOMER_MFA_PREVIOUS_KEYS_JSON') or '{}')
    if not isinstance(previous, dict) or len(previous) > 32 or kid in previous:
        raise ValueError('Invalid previous MFA key registry')
    for old_id, key in previous.items():
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', old_id):
            raise ValueError('Invalid previous MFA key id')
        mfa_key(key)
    if rotate:
        if len(previous) >= 32:
            raise ValueError('Retire migrated MFA keys before adding another key')
        previous[kid] = active
        new_id = 'mfa-' + secrets.token_hex(12)
        while new_id in previous:
            new_id = 'mfa-' + secrets.token_hex(12)
        updates['CUSTOMER_MFA_ACTIVE_KID'] = new_id
        updates['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'] = base64.b64encode(secrets.token_bytes(32)).decode()
    encoded = json.dumps(previous, separators=(',', ':'))
    if values.get('CUSTOMER_MFA_PREVIOUS_KEYS_JSON') != encoded:
        updates['CUSTOMER_MFA_PREVIOUS_KEYS_JSON'] = encoded
    return updates


def ensure(path, rotate_mfa=False):
    content = path.read_text(encoding='utf-8') if path.exists() else ''
    values = read_dotenv(content)
    registry = json.loads(values.get('PLATFORM_SERVICE_CREDENTIALS_JSON') or '{}')
    additions = ensure_mfa(values, rotate=rotate_mfa)
    for service in SERVICES:
        variable = 'PLATFORM_' + service.upper().replace('-', '_') + '_TOKEN'
        token = values.get(variable) or registry.get(service) or secrets.token_urlsafe(32)
        if service in registry and registry[service] != token:
            raise ValueError('Service credential differs from the issuer registry: ' + service)
        registry[service] = token
        if not values.get(variable):
            additions[variable] = token
    if not values.get('PLATFORM_OPERATOR_TOKEN'):
        additions['PLATFORM_OPERATOR_TOKEN'] = secrets.token_urlsafe(32)
    all_tokens = [*registry.values(), additions.get('PLATFORM_OPERATOR_TOKEN') or values['PLATFORM_OPERATOR_TOKEN']]
    if len(set(all_tokens)) != len(all_tokens) or any(not re.fullmatch(r'[A-Za-z0-9_-]{43,128}', token) for token in all_tokens):
        raise ValueError('Platform credentials must be unique random tokens of at least 32 bytes')
    encoded = json.dumps(registry, separators=(',', ':'))
    if values.get('PLATFORM_SERVICE_CREDENTIALS_JSON') != encoded:
        additions['PLATFORM_SERVICE_CREDENTIALS_JSON'] = encoded
    if additions:
        lines = [line for line in content.splitlines() if line.split('=', 1)[0] not in additions]
        for key, value in additions.items():
            # Compatible with both POSIX sourcing and Compose dotenv parsing.
            lines.append(key + "='" + value + "'")
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
        path.chmod(0o600)
    return bool(additions)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('dotenv', type=Path)
    parser.add_argument('--report-change', action='store_true')
    parser.add_argument('--rotate-mfa-key', action='store_true')
    args = parser.parse_args()
    changed = ensure(args.dotenv, rotate_mfa=args.rotate_mfa_key)
    if args.report_change:
        print('changed' if changed else 'unchanged')

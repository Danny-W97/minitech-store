#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd)"
source "$PROJECT_ROOT/scripts/control-center/lib/platform.sh"
source "$PROJECT_ROOT/scripts/control-center/lib/core.sh"
source "$PROJECT_ROOT/scripts/control-center/lib/secrets.sh"
audit_directory="$(mktemp -d "$PROJECT_ROOT/tmp/mfa-vault-test.XXXXXX")"
[[ "$audit_directory" == "$PROJECT_ROOT/tmp/mfa-vault-test."* ]] || exit 1
trap 'rm -rf -- "$audit_directory"' EXIT
SECRET_KEY_DIRECTORY="$audit_directory/keys"
SECRET_FILE_DIRECTORY="$audit_directory/secrets"
mkdir -p "$SECRET_KEY_DIRECTORY" "$SECRET_FILE_DIRECTORY"
age-keygen -o "$SECRET_KEY_DIRECTORY/test.agekey" >/dev/null 2>&1
recipient="$(age-keygen -y "$SECRET_KEY_DIRECTORY/test.agekey")"
printf 'CUSTOMER_JWT_SECRET="%s"\n' "$(openssl rand -base64 48)" > "$audit_directory/original.env"
SOPS_AGE_KEY_FILE="$SECRET_KEY_DIRECTORY/test.agekey" sops --encrypt --input-type dotenv --output-type dotenv \
  --age "$recipient" "$audit_directory/original.env" > "$SECRET_FILE_DIRECTORY/test.env.sops"
jwt_rotate test > "$audit_directory/actions.log"
plain="$(decrypt_profile_to_temp test)"
cp -- "$plain" "$audit_directory/first.env"
rm -f -- "$plain"
jwt_rotate test >> "$audit_directory/actions.log"
plain="$(decrypt_profile_to_temp test)"
cp -- "$plain" "$audit_directory/second.env"
rm -f -- "$plain"
mfa_rotate test >> "$audit_directory/actions.log"
plain="$(decrypt_profile_to_temp test)"
cp -- "$plain" "$audit_directory/mfa-rotated.env"
rm -f -- "$plain"
jwt_rotate test >> "$audit_directory/actions.log"
plain="$(decrypt_profile_to_temp test)"
cp -- "$plain" "$audit_directory/final.env"
rm -f -- "$plain"
python3 - "$PROJECT_ROOT" "$audit_directory" <<'PY'
import base64, hashlib, importlib.util, json, pathlib, sys
root, directory = map(pathlib.Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location('credentials', root / 'scripts/security/credentials.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
values = {name: module.read_dotenv((directory / (name + '.env')).read_text()) for name in ['original', 'first', 'second', 'mfa-rotated', 'final']}
old, first, second, rotated, final = (values[name] for name in ['original', 'first', 'second', 'mfa-rotated', 'final'])
assert first['CUSTOMER_MFA_LEGACY_KEY_BASE64'] == base64.b64encode(hashlib.sha256(old['CUSTOMER_JWT_SECRET'].encode()).digest()).decode()
assert first['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'] == second['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64']
assert len(json.loads(second['CUSTOMER_JWT_PUBLIC_KEYS_JSON'])) == 2
assert json.loads(rotated['CUSTOMER_MFA_PREVIOUS_KEYS_JSON'])[second['CUSTOMER_MFA_ACTIVE_KID']] == second['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64']
assert rotated['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'] == final['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64']
assert rotated['CUSTOMER_MFA_LEGACY_KEY_BASE64'] == final['CUSTOMER_MFA_LEGACY_KEY_BASE64']
assert len(json.loads(final['CUSTOMER_JWT_PUBLIC_KEYS_JSON'])) == 3
for profile in [first, second, final]:
    # PKCS#8 includes the rsaEncryption algorithm identifier, absent from PKCS#1.
    assert bytes.fromhex('06092a864886f70d010101') in base64.b64decode(profile['CUSTOMER_JWT_PRIVATE_KEY_BASE64'])
log = (directory / 'actions.log').read_text()
for profile in values.values():
    for key, secret in profile.items():
        if key.endswith(('KEY_BASE64', 'JWT_SECRET')): assert secret not in log
PY
python3 - "$audit_directory/final.env" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
p.write_text('\n'.join(line for line in p.read_text().splitlines() if not line.startswith('CUSTOMER_MFA_LEGACY_KEY_BASE64=')) + '\n')
PY
SOPS_AGE_KEY_FILE="$SECRET_KEY_DIRECTORY/test.agekey" sops --encrypt --input-type dotenv --output-type dotenv \
  --age "$recipient" "$audit_directory/final.env" > "$SECRET_FILE_DIRECTORY/test.env.sops"
export CUSTOMER_MFA_LEGACY_KEY_BASE64=retired-key-from-earlier-shell-load
load_profile_secrets test
[[ -z "${CUSTOMER_MFA_LEGACY_KEY_BASE64:-}" ]]
before="$(sha256sum "$SECRET_FILE_DIRECTORY/test.env.sops")"
real_sops="$(command -v sops)"
sops() { if [[ "${1:-}" == --encrypt ]]; then return 1; else "$real_sops" "$@"; fi; }
if jwt_rotate test >> "$audit_directory/actions.log" 2>&1; then exit 1; fi
[[ "$(sha256sum "$SECRET_FILE_DIRECTORY/test.env.sops")" == "$before" ]]
if mfa_rotate test >> "$audit_directory/actions.log" 2>&1; then exit 1; fi
[[ "$(sha256sum "$SECRET_FILE_DIRECTORY/test.env.sops")" == "$before" ]]
printf 'PASS: real SOPS JWT/MFA rotations retain keys; failed encryption preserves the vault; secrets stay hidden\n'

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[3]

@unittest.skipUnless(os.name=='posix','Bash QA entry points are exercised in WSL/CI')
class CredentialScopeTests(unittest.TestCase):
    def call(self,extra):
        script='source "$1"\nactive_profile() { printf dev; }\nload_profile_secrets() { export PLATFORM_OPERATOR_TOKEN=fixture-operator; printf \'LOADED\\n\'; }\nbash() { [[ "${QA_OPERATOR_TOKEN:-}" == fixture-operator ]] && printf \'GATE\\n\'; }\nqa_contract_test\n'
        env={key:value for key,value in os.environ.items() if key not in ['QA_OPERATOR_TOKEN','CONTRACT_API_BASE_URL','SECURITY_API_BASE_URL','LOAD_API_BASE_URL','LOAD_METRICS_URL']};env.update(extra)
        return subprocess.run(['bash','-c',script,'qa',str(ROOT/'scripts/control-center/lib/qa.sh')],env=env,text=True,capture_output=True)
    def test_local_profile_credential_is_scoped_to_the_gate(self):
        result=self.call({});self.assertEqual(result.returncode,0);self.assertIn('GATE',result.stdout)
    def test_custom_target_never_decrypts_the_profile_implicitly(self):
        result=self.call({'CONTRACT_API_BASE_URL':'http://untrusted.invalid'});self.assertNotEqual(result.returncode,0);self.assertNotIn('LOADED',result.stdout);self.assertNotIn('GATE',result.stdout)
    def test_explicit_test_credential_can_target_an_owned_dynamic_port(self):
        result=self.call({'CONTRACT_API_BASE_URL':'http://127.0.0.1:43991','QA_OPERATOR_TOKEN':'fixture-operator'});self.assertEqual(result.returncode,0);self.assertNotIn('LOADED',result.stdout)


@unittest.skipUnless(os.name=='posix','Bash QA entry points are exercised in WSL/CI')
class VerifyMvpOrderRetryTests(unittest.TestCase):
    def run_create(self,stub_body):
        with tempfile.TemporaryDirectory() as tmp:
            log=Path(tmp)/'calls.log'
            script=f'''set -Eeuo pipefail
source "$1"
C_RED=''; C_RESET=''
CALL_LOG="$2"
operator_curl() {{
  local key=''
  while (($#)); do
    if [[ "$1" == -H && "$2" == Idempotency-Key:* ]]; then key="${{2#Idempotency-Key: }}"; shift 2
    else shift; fi
  done
  printf '%s\\n' "$key" >> "$CALL_LOG"
  attempt_count="$(wc -l < "$CALL_LOG")"
{stub_body}
}}
APP_VERIFY_ORDER_RETRY_SECONDS=0 APP_VERIFY_ORDER_ATTEMPTS=4 verify_mvp_create_order http://127.0.0.1:18080/api corr-1 tok-1 '{{}}'
'''
            result=subprocess.run(['bash','-c',script,'qa',str(ROOT/'scripts/control-center/lib/qa.sh'),str(log)],text=True,capture_output=True)
            keys=log.read_text(encoding='utf-8').splitlines() if log.exists() else []
            return result,keys

    def test_retries_409_order_in_progress_with_same_idempotency_key(self):
        result,keys=self.run_create('''  if (( attempt_count == 1 )); then
    printf '{"status":409,"details":{"code":"ORDER_IN_PROGRESS","orderId":"1"}}\\n409'
  else
    printf '{"id":1,"inventoryReservationId":99}\\n201'
  fi''')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout,'{"id":1,"inventoryReservationId":99}')
        self.assertEqual(len(keys),2)
        self.assertTrue(keys[0] and keys[0]==keys[1])

    def test_fails_immediately_on_non_retryable_409_conflict(self):
        result,keys=self.run_create('''  printf '{"status":409,"details":{"code":"ORDER_IDEMPOTENCY_CONFLICT"}}\\n409' ''')
        self.assertNotEqual(result.returncode,0)
        self.assertIn('HTTP 409',result.stderr)
        self.assertEqual(len(keys),1)

    def test_fails_when_order_remains_in_progress_after_max_attempts(self):
        result,keys=self.run_create('''  printf '{"status":409,"details":{"code":"ORDER_IN_PROGRESS","orderId":"1"}}\\n409' ''')
        self.assertNotEqual(result.returncode,0)
        self.assertIn('HTTP 409',result.stderr)
        self.assertEqual(len(keys),4)
        self.assertEqual(len(set(keys)),1)

if __name__=='__main__':unittest.main()

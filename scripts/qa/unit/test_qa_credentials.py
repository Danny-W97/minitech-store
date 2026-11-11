import os
from pathlib import Path
import subprocess
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

if __name__=='__main__':unittest.main()

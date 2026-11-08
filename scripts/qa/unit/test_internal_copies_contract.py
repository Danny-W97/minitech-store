import copy,importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from test_platform_credentials import ROOT,load
gate=load('internal_copies_gate','scripts/qa/internal-copies-check.py')
class InternalCopyContractTests(unittest.TestCase):
    def test_real_contract_reads_only_source_metadata(self):
        with patch('socket.socket',side_effect=AssertionError('network forbidden')),patch('subprocess.run',side_effect=AssertionError('child forbidden')):
            result=gate.check(ROOT)
        self.assertEqual(11,result['resourcesReviewed']);self.assertFalse(result['runtimeConfigurationInspected']);self.assertTrue(result['externalLogging']['runtimeEvidenceRequired']);self.assertFalse(result['fullErasureComplete'])
    def fixture(self):
        temp=tempfile.TemporaryDirectory(dir=ROOT/'tmp');self.addCleanup(temp.cleanup);root=Path(temp.name)
        catalog=json.loads((ROOT/'scripts/qa/contracts/data-lifecycle.json').read_text(encoding='utf-8'));contract=json.loads((ROOT/'scripts/qa/contracts/internal-copies.json').read_text(encoding='utf-8'))
        paths=set(gate.CONFIGS)|{'scripts/qa/contracts/data-lifecycle.json','scripts/qa/contracts/internal-copies.json'}
        for entry in contract['resources'].values():paths.update([entry['procedure'],*entry['sources']])
        for name in paths:
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((ROOT/name).read_bytes())
        return root,catalog,contract
    def test_omitted_copy_and_premature_activation_fail_closed(self):
        root,catalog,contract=self.fixture();del contract['resources']['cc-job-output'];(root/'scripts/qa/contracts/internal-copies.json').write_text(json.dumps(contract))
        with self.assertRaisesRegex(ValueError,'Missing internal'):gate.check(root)
    def test_new_runtime_copy_requires_explicit_review(self):
        root,catalog,contract=self.fixture();catalog['copies'].append({**catalog['copies'][0],'id':'unknown-copy','kind':'runtime'});(root/'scripts/qa/contracts/data-lifecycle.json').write_text(json.dumps(catalog))
        with self.assertRaisesRegex(ValueError,'New internal copy'):gate.check(root)
    def test_detected_backend_is_review_required_and_never_proof_of_runtime_absence(self):
        root,catalog,contract=self.fixture();(root/'compose.apps.yaml').write_text('logging:\n  driver: gelf\n')
        result=gate.check(root);self.assertEqual('REQUIRES_REVIEW',result['externalLogging']['repositoryStatus']);self.assertTrue(result['externalLogging']['runtimeEvidenceRequired'])

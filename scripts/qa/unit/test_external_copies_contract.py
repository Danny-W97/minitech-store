import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from test_platform_credentials import ROOT,load

gate=load('external_copies_gate','scripts/qa/external-copies-check.py')

class ExternalCopyContractTests(unittest.TestCase):
    def fixture(self):
        directory=tempfile.TemporaryDirectory(dir=ROOT/'tmp');self.addCleanup(directory.cleanup)
        root=Path(directory.name)
        contract=json.loads((ROOT/'scripts/qa/contracts/external-copies.json').read_text(encoding='utf-8'))
        sources=set(contract['sourceFiles'])|{row['procedure'] for row in contract['resources'].values()}
        sources|={'scripts/qa/contracts/external-copies.json','scripts/qa/contracts/data-lifecycle.json'}
        for name in sources:
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((ROOT/name).read_bytes())
        return root,contract

    def save(self,root,contract):
        (root/'scripts/qa/contracts/external-copies.json').write_text(json.dumps(contract),encoding='utf-8')

    def test_real_contract_reports_manual_blockers_without_any_network_or_child(self):
        with patch('socket.socket',side_effect=AssertionError('network forbidden')),patch('subprocess.run',side_effect=AssertionError('child forbidden')):
            result=gate.check(ROOT)
        self.assertEqual(6,result['resourcesReviewed']);self.assertEqual(0,result['providerContacts'])
        self.assertFalse(result['runtimeConfigurationInspected']);self.assertFalse(result['fullA43Complete'])
        self.assertIn('A05_DEFERRED',result['requiredManualEvidence'])

    def test_missing_scope_owner_or_procedure_is_rejected(self):
        root,original=self.fixture()
        for mutation in ('missing','owner','procedure'):
            contract=copy.deepcopy(original)
            if mutation=='missing':del contract['resources']['deleted-chat-status']
            else:contract['resources']['deleted-chat-status'][mutation]=''
            self.save(root,contract)
            with self.assertRaises(ValueError):gate.check(root)

    def test_completion_activation_and_hidden_provider_dependencies_are_rejected(self):
        root,original=self.fixture()
        for key,value in [('fullA43Complete',True),('fullErasureComplete',True),('retentionActivation','active'),('requiredEvidence',[])]:
            contract={**original,key:value};self.save(root,contract)
            with self.assertRaises(ValueError):gate.check(root)
        contract=copy.deepcopy(original);contract['resources']['assistant-provider-copy']['status']='IMPLEMENTED_BOUNDED_RAM';self.save(root,contract)
        with self.assertRaisesRegex(ValueError,'Provider evidence'):gate.check(root)

    def test_new_approval_copy_cannot_disappear_from_inventory(self):
        root,contract=self.fixture();path=root/'scripts/qa/contracts/data-lifecycle.json'
        inventory=json.loads(path.read_text(encoding='utf-8'));inventory['copies']=[r for r in inventory['copies'] if r['id']!='assistant-egress-approvals'];path.write_text(json.dumps(inventory),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'Unclassified'):gate.check(root)
        inventory=json.loads((ROOT/'scripts/qa/contracts/data-lifecycle.json').read_text(encoding='utf-8'))
        inventory['copies'].append({'id':'new-provider','kind':'external'})
        path.write_text(json.dumps(inventory),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'New external copy'):gate.check(root)

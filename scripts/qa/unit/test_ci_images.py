import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('ci_image_boundary_tests',ROOT/'scripts/qa/ci-images-check.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


class CiImageBoundaryTests(unittest.TestCase):
    def fixture(self):
        temp=tempfile.TemporaryDirectory(prefix='ci-image-boundary-',dir=ROOT/'tmp');self.addCleanup(temp.cleanup)
        root=Path(temp.name)
        workflow=yaml.safe_load((ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8'))
        action=yaml.safe_load((ROOT/'.github/actions/build-scan-image/action.yml').read_text(encoding='utf-8'))
        return root,workflow,action

    def write(self,root,workflow,action):
        for path,doc in [('.github/workflows/ci.yml',workflow),('.github/actions/build-scan-image/action.yml',action)]:
            file=root/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(yaml.safe_dump(doc,sort_keys=False),encoding='utf-8')

    def test_current_workflow_preserves_readonly_pr_and_scan_before_publication(self):
        result=gate.check();self.assertEqual('PASS',result['status']);self.assertFalse(result['prPublication']);self.assertFalse(result['githubRunObserved'])

    def test_pr_write_oidc_secrets_registry_login_and_cache_export_are_rejected(self):
        root,original,action=self.fixture()
        for kind in ['permissions','oidc','secrets','login','cache']:
            workflow=copy.deepcopy(original);job=workflow['jobs']['pr-images']
            if kind=='permissions':job['permissions']['packages']='write'
            elif kind=='oidc':job['permissions']['id-token']='write'
            elif kind=='secrets':job['steps'].append({'run':'echo secret','env':{'TOKEN':'${{ secrets.SYNTHETIC_TOKEN }}'}})
            elif kind=='login':job['steps'].append({'uses':'docker/login-action@v3'})
            else:next(s for s in job['steps'] if s.get('uses')=='./.github/actions/build-scan-image')['with']['export-cache']='true'
            self.write(root,workflow,action)
            with self.subTest(kind=kind),self.assertRaises(ValueError):gate.check(root)

    def test_empty_or_failed_plan_cannot_silently_pass_the_stable_gate(self):
        root,workflow,action=self.fixture()
        workflow['jobs']['pr-image-gate']['steps'][0]['run']='echo passed'
        self.write(root,workflow,action)
        with self.assertRaises(ValueError):gate.check(root)

    @unittest.skipUnless(os.name=='posix','Bash execution is checked in WSL/CI')
    def test_actual_gate_script_propagates_failure_and_accepts_only_valid_empty_skip(self):
        _,workflow,_=self.fixture();script=workflow['jobs']['pr-image-gate']['steps'][0]['run']
        for plan,images,build,ok in [('success','true','success',True),('success','false','skipped',True),
                ('failure','false','skipped',False),('success','true','failure',False),
                ('success','true','cancelled',False),('success','','skipped',False),('success','false','failure',False)]:
            run=subprocess.run(['bash','-e','-c',script],env={'PATH':os.environ['PATH'],'PLAN_RESULT':plan,'HAS_IMAGES':images,'BUILD_RESULT':build},capture_output=True,timeout=10)
            with self.subTest(plan=plan,images=images,build=build):self.assertEqual(ok,run.returncode==0)

    def test_filtered_pr_trigger_or_shallow_checkout_is_rejected(self):
        root,original,action=self.fixture()
        workflow=copy.deepcopy(original);workflow.get('on',workflow.get(True))['pull_request']['paths-ignore']=['docs/**'];self.write(root,workflow,action)
        with self.assertRaises(ValueError):gate.check(root)
        workflow=copy.deepcopy(original);workflow['jobs']['pr-image-plan']['steps'][0]['with']['fetch-depth']=1;self.write(root,workflow,action)
        with self.assertRaises(ValueError):gate.check(root)

    def test_scan_soft_fail_early_push_and_shared_cache_collision_are_rejected(self):
        root,workflow,original=self.fixture()
        for kind in ['scan','scan-target','early-push','cache','raw-artifact']:
            action=copy.deepcopy(original)
            if kind=='scan':next(s for s in action['runs']['steps'] if s.get('id')=='scan')['continue-on-error']=True
            elif kind=='scan-target':next(s for s in action['runs']['steps'] if s.get('id')=='scan')['env']['A44_IMAGE']='unrelated-image:latest'
            elif kind=='early-push':next(s for s in action['runs']['steps'] if s.get('id')=='jvm')['with']['push']=True
            elif kind=='cache':next(s for s in action['runs']['steps'] if s.get('id')=='jvm')['with']['cache-from']='type=gha'
            else:next(s for s in action['runs']['steps'] if s.get('uses','').startswith('actions/upload-artifact@'))['with']['path']='**/*'
            self.write(root,workflow,action)
            with self.subTest(kind=kind),self.assertRaises(ValueError):gate.check(root)

    def test_reducing_release_inventory_or_removing_production_gate_is_rejected(self):
        root,original,action=self.fixture()
        for kind in ['inventory','release-guard']:
            workflow=copy.deepcopy(original)
            if kind=='inventory':workflow['jobs']['build-images']['strategy']['matrix']={'service':['frontend']}
            else:workflow['jobs']['deploy-kubernetes']['steps']=[s for s in workflow['jobs']['deploy-kubernetes']['steps'] if 'audit_readiness.py --release' not in s.get('run','')]
            self.write(root,workflow,action)
            with self.subTest(kind=kind),self.assertRaises(ValueError):gate.check(root)


if __name__=='__main__':unittest.main()

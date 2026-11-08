import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('readiness_config',ROOT/'scripts/qa/readiness-check.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.root=Path(self.temporary.name);self.addCleanup(self.temporary.cleanup)
        files=[ROOT/'deploy/components.json',ROOT/'deploy/k8s/base/deployments.yaml']
        files += list(ROOT.glob('*-service/src/main/resources/application*.y*ml'))+list(ROOT.glob('api-gateway/src/main/resources/application*.y*ml'))
        for path in files:
            dest=self.root/path.relative_to(ROOT);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,dest)
    def append(self,key,value,service='inventory-service',profile=''):
        p=self.root/service/'src/main/resources'/('application'+profile+'.yaml')
        data=gate.flatten(yaml.safe_load(p.read_text()));data[key]=value
        p.write_text(yaml.safe_dump(data,sort_keys=False))
    def test_all_jvm_roles_and_profiles_are_reviewed(self):
        r=gate.check(self.root);self.assertEqual(15,r['jvmComponents']);self.assertEqual(14,r['sqlReadinessComponents'])
        self.assertGreaterEqual(r['configurationsReviewed'],29);self.assertEqual(45,r['kubernetesProbePaths'])
        self.assertFalse(r['runtimeExecutionVerified']);self.assertFalse(r['fullA35Complete'])
    def test_omitting_sql_or_including_all_dependencies_is_rejected(self):
        for value in ('readinessState','*','readinessState,db,redis','readinessState,db,mail'):
            self.append('management.endpoint.health.group.readiness.include',value)
            with self.subTest(value=value),self.assertRaises(ValueError):gate.check(self.root)
    def test_external_liveness_and_gateway_sql_dependency_are_rejected(self):
        self.append('management.endpoint.health.group.liveness.include','livenessState,db')
        with self.assertRaises(ValueError):gate.check(self.root)
        self.append('management.endpoint.health.group.liveness.include','livenessState')
        self.append('management.endpoint.health.group.readiness.include','readinessState,db',service='api-gateway')
        with self.assertRaises(ValueError):gate.check(self.root)
    def test_profile_override_cannot_bypass_base_policy(self):
        self.append('management.endpoint.health.group.readiness.include','readinessState',profile='-prod')
        with self.assertRaises(ValueError):gate.check(self.root)
    def test_disabled_indicators_or_hidden_exclusions_are_rejected(self):
        for key in ('management.endpoint.health.probes.enabled','management.health.db.enabled','management.health.readinessstate.enabled'):
            self.append(key,'false')
            with self.subTest(key=key),self.assertRaises(ValueError):gate.check(self.root)
            self.append(key,'true')
        self.append('management.endpoint.health.group.readiness.exclude','db')
        with self.assertRaises(ValueError):gate.check(self.root)
    def test_anonymous_details_and_successful_down_mapping_are_rejected(self):
        for key,value in (('management.endpoint.health.group.readiness.show-details','always'),
                          ('management.endpoint.health.group.liveness.show-components','always'),
                          ('management.endpoint.health.status.http-mapping.down','200')):
            self.append(key,value)
            with self.subTest(key=key),self.assertRaises(ValueError):gate.check(self.root)
            self.append(key,'503' if 'mapping' in key else 'never')
    def test_incorrect_kubernetes_probe_path_is_rejected(self):
        p=self.root/'deploy/k8s/base/deployments.yaml';docs=list(yaml.safe_load_all(p.read_text()))
        d=next(d for d in docs if d.get('metadata',{}).get('name')=='inventory-service')
        d['spec']['template']['spec']['containers'][0]['livenessProbe']['httpGet']['path']='/actuator/health'
        p.write_text(yaml.safe_dump_all(docs))
        with self.assertRaises(ValueError):gate.check(self.root)
    def test_duplicate_flattened_key_is_not_silently_overwritten(self):
        with self.assertRaises(ValueError):gate.flatten({'management':{'key':True},'management.key':False})


if __name__=='__main__':unittest.main()

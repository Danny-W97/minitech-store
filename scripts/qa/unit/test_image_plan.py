import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/deploy'))
spec = importlib.util.spec_from_file_location('image_plan_tests', ROOT / 'scripts/deploy/image-plan.py')
planner = importlib.util.module_from_spec(spec);spec.loader.exec_module(planner)
spec = importlib.util.spec_from_file_location('image_evidence_tests', ROOT / 'scripts/deploy/image-evidence.py')
evidence = importlib.util.module_from_spec(spec);spec.loader.exec_module(evidence)


class ImagePlanTests(unittest.TestCase):
    def names(self, paths):
        return set(planner.select(ROOT, paths)['matrix']['service'])

    def test_direct_services_and_frontend_are_selected_without_http_dependents(self):
        self.assertEqual({'product-service'}, self.names(['product-service/src/main/java/Fixture.java']))
        self.assertEqual({'frontend'}, self.names(['frontend/app.js']))
        self.assertEqual({'frontend','order-service'}, self.names(['frontend/app.js','order-service/src/main/resources/application.yaml']))

    def test_shared_parent_wrapper_library_and_every_pom_select_all_jvm_images(self):
        expected={row['name'] for row in planner.load(ROOT)['components'] if row['runtime']=='jvm'}
        for path in ['pom.xml','mvnw','.mvn/wrapper/maven-wrapper.properties','platform-security/src/main/java/Fixture.java','qa-tests/pom.xml','product-service/pom.xml','deploy/docker/service.Dockerfile']:
            with self.subTest(path=path):self.assertEqual(expected,self.names([path]))

    def test_packaged_customer_policy_does_not_skip_its_consumer(self):
        self.assertEqual({'customer-service'},self.names(['scripts/qa/contracts/technical-data-classification.json']))

    def test_shared_pipeline_context_and_unknown_inputs_conservatively_select_all(self):
        for path in ['.dockerignore','deploy/components.json','.github/workflows/ci.yml','.github/actions/build-scan-image/action.yml','scripts/deploy/image-plan.py','new-shared-input.toml','deploy/docker/new-config.conf']:
            with self.subTest(path=path):self.assertEqual(16,len(self.names([path])))

    def test_docs_and_qa_only_skip_images_but_resources_inside_module_do_not(self):
        self.assertEqual(set(),self.names(['docs/readme.md','scripts/qa/unit/test_fixture.py','qa-tests/src/test/java/Fixture.java']))
        self.assertEqual({'product-service'},self.names(['product-service/src/main/resources/help.md']))

    def test_invalid_or_unusual_paths_never_become_an_empty_matrix(self):
        for path in ['../outside','/absolute','path\\outside','odd\nname','$(touch pwned)']:
            with self.subTest(path=path):self.assertEqual(16,len(self.names([path])))

    def fixture(self):
        temp=tempfile.TemporaryDirectory(prefix='image-plan-',dir=ROOT/'tmp');self.addCleanup(temp.cleanup)
        root=Path(temp.name)
        for path in ['pom.xml','deploy/components.json',*[str(p.relative_to(ROOT)) for p in ROOT.glob('*/pom.xml')]]:
            target=root/path;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,target)
        return root

    def test_transitive_maven_dependents_follow_the_reactor_graph(self):
        root=self.fixture()
        for owner,dependency in [('order-service','product-service'),('inventory-service','order-service')]:
            file=root/owner/'pom.xml';text=file.read_text(encoding='utf-8')
            text=text.replace('<dependencies>','<dependencies><dependency><groupId>org.demo</groupId><artifactId>'+dependency+'</artifactId><version>0.0.1-SNAPSHOT</version></dependency>',1)
            file.write_text(text,encoding='utf-8')
        self.assertEqual({'product-service','order-service','inventory-service'},set(planner.select(root,['product-service/src/main/java/Fixture.java'])['matrix']['service']))

    def test_missing_git_revision_or_broken_graph_falls_back_to_full_build(self):
        result=planner.plan(ROOT,'pull_request',{'pull_request':{'base':{'sha':'invalid'}}},'f'*40)
        self.assertEqual((16,'fallback-full'),(result['selectedImages'],result['selectionMode']))
        root=self.fixture();(root/'product-service/pom.xml').write_text('invalid',encoding='utf-8')
        from unittest.mock import patch
        with patch.object(planner,'changed_paths',return_value=['product-service/src/main/java/Fixture.java']):
            self.assertEqual('BUILD_GRAPH_UNAVAILABLE',planner.plan(root,'pull_request',{'pull_request':{'base':{'sha':'a'*40}}},'f'*40)['reasons'][0]['code'])

    def test_real_git_diff_includes_deleted_and_both_renamed_paths(self):
        root=self.fixture()
        env={**os.environ,'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':os.devnull}
        def git(*args):
            return subprocess.check_output(['git','-c','core.fsmonitor=false','-c','commit.gpgsign=false',*args],cwd=root,env=env,stderr=subprocess.DEVNULL).decode().strip()
        git('init','--template=');git('config','user.email','fixture@example.invalid');git('config','user.name','Image QA')
        path=root/'frontend/old.js';path.parent.mkdir();path.write_text('fixture')
        old=root/'product-service/src/removed.txt';old.parent.mkdir(parents=True);old.write_text('fixture')
        git('add','.');git('commit','-m','base');base=git('rev-parse','HEAD')
        path.rename(path.with_name('new.js'));old.unlink();git('add','-A');git('commit','-m','changed');head=git('rev-parse','HEAD')
        self.assertEqual({'frontend/old.js','frontend/new.js','product-service/src/removed.txt'},set(planner.changed_paths(root,base,head)))
        result=planner.plan(root,'pull_request',{'pull_request':{'base':{'sha':base}}},head)
        self.assertEqual({'frontend','product-service'},set(result['matrix']['service']))

    def test_non_pr_events_always_select_full_inventory_and_bad_inventory_fails(self):
        for event in ['push','workflow_dispatch']:
            self.assertEqual(16,planner.plan(ROOT,event,{},'f'*40)['selectedImages'])
        root=self.fixture();file=root/'deploy/components.json';file.write_text('{"schemaVersion":1,"components":[{}]}')
        with self.assertRaises((ValueError,KeyError)):planner.select(root,[],force_all=True)


class ImageEvidenceTests(unittest.TestCase):
    def directory(self):
        temp=tempfile.TemporaryDirectory(prefix='image-evidence-',dir=ROOT/'tmp');self.addCleanup(temp.cleanup)
        return Path(temp.name)

    def test_success_records_image_identity_and_separate_timings_without_raw_data(self):
        root=self.directory();evidence.mark(root,'build',now=0);evidence.mark(root,'scan',now=2_000_000_000)
        row=evidence.finish(root,'product-service','a'*40,'pull_request','success','success','sha256:'+'b'*64,now=5_000_000_000)
        self.assertEqual(('PASS',2.0,3.0,5.0),(row['status'],row['buildSeconds'],row['scanSeconds'],row['totalSeconds']))
        self.assertFalse(row['publicationPerformedByThisTool']);self.assertNotIn('environment',row)

    def test_build_or_scan_failure_cannot_produce_passed_evidence(self):
        for build,scan in [('failure','skipped'),('success','failure'),('cancelled','skipped')]:
            root=self.directory();evidence.mark(root,'build',now=1)
            row=evidence.finish(root,'frontend','a'*40,'pull_request',build,scan,now=3)
            self.assertEqual('FAILED',row['status'])

    def test_success_without_scan_clock_image_or_with_invalid_clock_is_rejected(self):
        root=self.directory();evidence.mark(root,'build',now=10)
        with self.assertRaises(ValueError):evidence.finish(root,'frontend','a'*40,'qa','success','success',now=11)
        with self.assertRaises(ValueError):evidence.finish(root,'frontend','a'*40,'qa','failure','skipped',now=9)
        with self.assertRaises(ValueError):evidence.mark(root,'build',now=11)


if __name__=='__main__':unittest.main()

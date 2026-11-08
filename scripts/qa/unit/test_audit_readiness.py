import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('readiness',ROOT/'scripts/qa/audit_readiness.py');readiness=importlib.util.module_from_spec(spec);spec.loader.exec_module(readiness)

class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        files=['scripts/qa/contracts/p1-evidence.json','docs/AUDYT_APLIKACJI_2026-10-23.md','.github/workflows/ci.yml']
        matrix=json.loads((ROOT/files[0]).read_text(encoding='utf-8'))['findings']
        files.extend(item['path'] for entry in matrix.values() for item in entry['evidence'])
        for name in set(files):
            target=self.root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,target)
    def tearDown(self):self.temp.cleanup()
    def test_current_map_explicitly_reports_open_p1(self):
        result=readiness.verify(self.root);self.assertEqual(result['p1Findings'],14);self.assertEqual(result['completed'],13);self.assertFalse(result['p1Ready']);self.assertEqual(result['openP1'],['A05']);self.assertNotIn('A06',result['openP1']);self.assertNotIn('A09',result['openP1']);self.assertNotIn('A10',result['openP1']);self.assertNotIn('A11',result['openP1']);self.assertNotIn('A21',result['openP1'])
    def test_release_is_blocked_by_unfinished_p1(self):
        with self.assertRaisesRegex(ValueError,'Production blocked'):readiness.verify(self.root,release=True)
    def test_removing_or_echoing_critical_browser_commands_cannot_pass(self):
        path=self.root/'.github/workflows/ci.yml';original=path.read_text(encoding='utf-8')
        for command in readiness.BROWSER_CI_COMMANDS:
            for substitute in ('echo removed','echo '+command,'# '+command):
                path.write_text(original.replace(command,substitute),encoding='utf-8')
                with self.subTest(command=command,substitute=substitute),self.assertRaisesRegex(ValueError,'critical browser CI command missing'):readiness.verify(self.root)
    def test_omitting_required_browser_from_ci_cannot_pass(self):
        path=self.root/'.github/workflows/ci.yml';original=path.read_text(encoding='utf-8')
        for project in ('firefox','webkit','mobile-chrome'):
            path.write_text(original.replace(readiness.BROWSER_CI_COMMANDS[1],readiness.BROWSER_CI_COMMANDS[1].replace('--project='+project,'')),encoding='utf-8')
            with self.subTest(project=project),self.assertRaisesRegex(ValueError,'critical browser CI command missing'):readiness.verify(self.root)
    def test_ci_wiring_is_never_reported_as_execution_or_production_readiness(self):
        result=readiness.verify(self.root);self.assertTrue(result['criticalBrowserCiWired'])
        self.assertFalse(result['criticalBrowserExecutionVerified']);self.assertFalse(result['p1Ready'])
    def test_removing_a_regression_from_ci_fails(self):
        path=self.root/'.github/workflows/ci.yml';path.write_text(path.read_text(encoding='utf-8').replace('pnpm run test:unit','echo skipped'),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'CI command missing'):readiness.verify(self.root)
    def test_a_commented_out_ci_command_is_not_executable_evidence(self):
        path=self.root/'.github/workflows/ci.yml';path.write_text(path.read_text(encoding='utf-8').replace('run: pnpm run test:unit','run: echo removed # pnpm run test:unit'),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'CI command missing'):readiness.verify(self.root)
    def test_echoing_a_command_is_not_running_the_test(self):
        path=self.root/'.github/workflows/ci.yml';path.write_text(path.read_text(encoding='utf-8').replace('run: pnpm run test:unit','run: echo pnpm run test:unit'),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'CI command missing'):readiness.verify(self.root)
    def test_removing_a_completed_p1_test_fails(self):
        (self.root/'scripts/qa/unit/api-config.test.cjs').write_text('',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'evidence missing'):readiness.verify(self.root)
    def test_claiming_a_fix_without_updating_evidence_fails(self):
        path=self.root/'docs/AUDYT_APLIKACJI_2026-10-23.md';text=path.read_text(encoding='utf-8');lines=text.splitlines()
        for index,line in enumerate(lines):
            if line.startswith('| [A05]'):
                columns=line.split('|');columns[-2]=' ✅ Wykonane ';lines[index]='|'.join(columns)
        path.write_text('\n'.join(lines),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'update evidence'):readiness.verify(self.root)

if __name__=='__main__':unittest.main()

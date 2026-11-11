import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('critical_browser', ROOT / 'scripts/qa/critical-browser-check.py')
gate = importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


def report(projects=gate.PROJECTS):
    return {'errors': [], 'stats': {'unexpected': 0, 'flaky': 0}, 'suites': [{'suites': [{
        'specs': [{'title': f'[{case}] fixture', 'file': gate.SOURCE, 'ok': True,
                   'tests': [{'projectName': project, 'expectedStatus': 'passed', 'status': 'expected',
                              'results': [{'status': 'passed', 'retry': 0}]} for project in projects]}
                  for case in gate.CASES]}]}]}


class CriticalBrowserTests(unittest.TestCase):
    def row(self, r):return r['suites'][0]['suites'][0]['specs'][0]

    def test_accepts_complete_matrix_and_separate_ci_reports(self):
        for reports in ([report()], [report(('chromium',)), report(('firefox', 'webkit', 'mobile-chrome'))]):
            result = gate.check(reports);self.assertEqual(28, result['passedCombinations'])
            self.assertFalse(result['fullA33Complete']);self.assertFalse(result['productionReady'])

    def test_missing_case_browser_or_all_results_cannot_pass(self):
        for mutation in ('case', 'browser', 'suite', 'all'):
            r = report()
            if mutation == 'case':r['suites'][0]['suites'][0]['specs'].pop()
            if mutation == 'browser':self.row(r)['tests'].pop()
            if mutation == 'suite':r.pop('suites')
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):gate.check([] if mutation == 'all' else [r])

    def test_skip_expected_failure_interruption_and_flaky_retry_are_rejected(self):
        for mutation in ('skipped', 'failed', 'interrupted', 'timedOut', 'expected-failure', 'retry', 'two-results', 'no-results'):
            r = report();test = self.row(r)['tests'][0]
            if mutation in ('skipped', 'failed', 'interrupted', 'timedOut'):test['results'][0]['status'] = mutation
            if mutation == 'expected-failure':test['expectedStatus'] = 'failed'
            if mutation == 'retry':test['results'][0]['retry'] = 1
            if mutation == 'two-results':test['results'].append(copy.deepcopy(test['results'][0]))
            if mutation == 'no-results':test['results'] = []
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):gate.check([r])

    def test_duplicate_unknown_id_or_wrong_file_cannot_inflate_coverage(self):
        for mutation in ('duplicate', 'unknown-id', 'wrong-file', 'missing-id', 'unknown-project'):
            r = report();row = self.row(r)
            if mutation == 'duplicate':row['tests'].append(copy.deepcopy(row['tests'][0]))
            if mutation == 'unknown-id':row['title'] = '[A33-C99] fixture'
            if mutation == 'wrong-file':row['file'] = 'unrelated.spec.ts'
            if mutation == 'missing-id':row['title'] = 'fixture'
            if mutation == 'unknown-project':row['tests'][0]['projectName'] = 'unknown'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):gate.check([r])

    def test_report_errors_failures_or_missing_counters_fail_closed(self):
        for mutation in ('error', 'missing-errors', 'failed', 'flaky', 'missing-counter'):
            r = report()
            if mutation == 'error':r['errors'].append({'message': 'private-fixture'})
            if mutation == 'missing-errors':r.pop('errors')
            if mutation == 'failed':r['stats']['unexpected'] = 1
            if mutation == 'flaky':r['stats']['flaky'] = 1
            if mutation == 'missing-counter':r['stats'].pop('flaky')
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):gate.check([r])

    def test_empty_or_false_ok_spec_does_not_count_as_executed(self):
        for mutation in ('empty', 'not-ok', 'outcome'):
            r = report();row = self.row(r)
            if mutation == 'empty':row['tests'] = []
            if mutation == 'not-ok':row['ok'] = False
            if mutation == 'outcome':row['tests'][0]['status'] = 'unexpected'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):gate.check([r])

    def test_read_limit_and_malformed_json_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'report.json';p.write_text('[]')
            with patch.object(gate, 'MAX_REPORT_BYTES', 1), self.assertRaises(ValueError):gate.read_report(p)
            p.write_text('{')
            with self.assertRaises(ValueError):gate.read_report(p)

    def test_cli_records_failure_without_leaking_report_content(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'in.json';output = Path(d) / 'out.json'
            path.write_text(json.dumps({'errors': [{'message': 'secret-fixture-value'}]}))
            with patch('sys.argv', ['gate', '--report', str(path), '--output', str(output)]), patch('builtins.print'):
                self.assertEqual(1, gate.main())
            text = output.read_text();self.assertNotIn('secret-fixture-value', text)
            self.assertEqual('FAILED', json.loads(text)['status'])


if __name__ == '__main__':unittest.main()

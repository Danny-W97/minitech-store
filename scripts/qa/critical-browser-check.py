#!/usr/bin/env python3
"""A33: require actual, first-attempt browser results for the checkout recovery matrix."""
import argparse
import json
from pathlib import Path
import re

PROJECTS = ('chromium', 'firefox', 'webkit', 'mobile-chrome')
CASES = tuple(f'A33-C{n:02d}' for n in range(1, 8))
SOURCE = 'checkout/checkout-recovery.spec.ts'
MAX_REPORT_BYTES = 16 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check(reports):
    require(bool(reports), 'Missing browser reports')
    found = set()
    for report in reports:
        require(isinstance(report, dict), 'Invalid browser report')
        require(report.get('errors') == [], 'Browser report has errors or omits error status')
        stats = report.get('stats', {})
        require(all(type(stats.get(k)) is int and stats[k] == 0 for k in ('unexpected', 'flaky')),
                'Browser report has failures, flaky results or missing counters')

        def suites(rows):
            require(isinstance(rows, list), 'Missing browser suites')
            for suite in rows:
                require(isinstance(suite, dict), 'Invalid browser suite')
                for spec in suite.get('specs', []):
                    title = spec.get('title', '')
                    match = re.match(r'^\[(A33-C\d{2})\] ', title)
                    file = spec.get('file', '').replace('\\', '/')
                    critical_file = file == SOURCE or file.endswith('/' + SOURCE)
                    if not critical_file and not match:
                        continue
                    require(critical_file and match is not None and match[1] in CASES,
                            'Unknown critical case or incorrect test source')
                    require(spec.get('ok') is True and bool(spec.get('tests')), 'Critical case did not pass')
                    for test in spec['tests']:
                        project = test.get('projectName')
                        require(project in PROJECTS, 'Unknown critical browser project')
                        key = (match[1], project)
                        require(key not in found, 'Duplicate critical browser result')
                        require(test.get('expectedStatus') == 'passed' and test.get('status') == 'expected',
                                f'Critical case skipped, failed or expected to fail: {key[0]}/{project}')
                        results = test.get('results', [])
                        require(len(results) == 1 and results[0].get('status') == 'passed'
                                and results[0].get('retry') == 0,
                                f'Critical case needs one passing first attempt: {key[0]}/{project}')
                        found.add(key)
                suites(suite.get('suites', []))

        suites(report.get('suites'))
    expected = {(case, project) for case in CASES for project in PROJECTS}
    require(found == expected, 'Incomplete critical browser matrix: ' + ', '.join(
        f'{case}/{project}' for case, project in sorted(expected - found)))
    return {'status': 'PASS', 'cases': len(CASES), 'projects': list(PROJECTS),
            'passedCombinations': len(found), 'skippedCriticalCases': 0, 'retriedCriticalCases': 0,
            'scope': 'deterministic browser API fixtures; not external Stripe or deployed environment acceptance',
            'fullA33Complete': False, 'productionReady': False}


def read_report(path):
    with Path(path).open('rb') as stream:
        raw = stream.read(MAX_REPORT_BYTES + 1)
    require(len(raw) <= MAX_REPORT_BYTES, 'Browser report exceeds size limit')
    return json.loads(raw)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        result = check([read_report(path) for path in args.report])
    except (ValueError, OSError, KeyError, TypeError, AttributeError) as error:
        # Do not copy report content, request details or credentials into the gate result.
        reason = str(error) if type(error) is ValueError else 'Critical browser evidence is missing, invalid or incomplete'
        result = {'status': 'FAILED', 'reason': reason,
                  'fullA33Complete': False, 'productionReady': False}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())

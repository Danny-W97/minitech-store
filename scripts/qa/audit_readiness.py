#!/usr/bin/env python3
"""Check P1 evidence against the audit. A release check rejects every unfinished P1."""
import argparse
import json
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
BROWSER_CI_COMMANDS=(
    'pnpm run test:ui --reporter=line,json',
    'pnpm exec playwright test e2e/tests/checkout/checkout-recovery.spec.ts --project=firefox --project=webkit --project=mobile-chrome --retries=0 --fail-on-flaky-tests --reporter=line,json',
    'python3 scripts/qa/critical-browser-check.py --report gitIgnoreOwn/runtime/playwright/storefront-chromium.json --report gitIgnoreOwn/runtime/playwright/checkout-recovery.json --output gitIgnoreOwn/runtime/playwright/critical-browser-gate.json',
)

def verify(root=ROOT,release=False):
    root=Path(root);matrix=json.loads((root/'scripts/qa/contracts/p1-evidence.json').read_text(encoding='utf-8'))['findings']
    audit=(root/'docs/AUDYT_APLIKACJI_2026-10-23.md').read_text(encoding='utf-8');ci=(root/'.github/workflows/ci.yml').read_text(encoding='utf-8')
    actual={}
    for line in audit.splitlines():
        if re.match(r'\| \[A\d+\].*?\| P1 \|',line):
            parts=line.split('|');key=re.search(r'A\d+',parts[1]).group();status=parts[-2].strip()
            actual[key]='partial' if 'Częściowo' in status else 'done' if '✅' in status or 'Naprawione' in status else 'open'
    # Evidence must be in executable run steps, not a comment or an artifact path.
    runs=[];lines=ci.splitlines()
    for index,line in enumerate(lines):
        match=re.match(r'^(\s*)run:\s*(.*)$',line)
        if not match:continue
        indent=len(match.group(1));value=match.group(2)
        if value.startswith(('|','>')):
            for child in lines[index+1:]:
                if child.strip() and len(child)-len(child.lstrip())<=indent:break
                if not child.lstrip().startswith('#'):runs.append(child)
        else:runs.append(value.split("#",1)[0])
    ci_commands=[line.strip() for value in runs for line in value.splitlines() if line.strip()]
    errors=[]
    for command in BROWSER_CI_COMMANDS:
        if not any(line==command or line.startswith(command+' ') for line in ci_commands):
            errors.append('A33: critical browser CI command missing: '+command)
    if set(actual)!=set(matrix):errors.append('P1 register and evidence inventory disagree')
    pending=[]
    for key,entry in matrix.items():
        if actual.get(key)!=entry['state']:errors.append(key+': update evidence when changing audit status')
        if entry['state']=='done' and (not entry['evidence'] or not entry['ciCommands']):errors.append(key+': completed P1 has no executable evidence')
        if entry['state']!='done':
            pending.append(key)
            if not entry.get('remaining'):errors.append(key+': missing description of unfinished coverage')
        for evidence in entry['evidence']:
            path=root/evidence['path']
            if not path.is_file() or evidence['marker'] not in path.read_text(encoding='utf-8'):errors.append(key+': test evidence missing: '+evidence['path'])
        for command in entry['ciCommands']:
            if not any(line==command or line.startswith(command+" ") for line in ci_commands):errors.append(key+': required CI command missing: '+command)
    if release and pending:errors.append('Production blocked by unfinished P1: '+', '.join(pending))
    if errors:raise ValueError('; '.join(errors))
    return {'p1Findings':len(matrix),'completed':len(matrix)-len(pending),'p1Ready':not pending,'openP1':pending,
            'criticalBrowserCiWired':True,'criticalBrowserExecutionVerified':False}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--release',action='store_true');args=parser.parse_args()
    try:print(json.dumps(verify(release=args.release),indent=2));return 0
    except (ValueError,OSError,KeyError) as error:print('FAIL:',error);return 1
if __name__=='__main__':raise SystemExit(main())

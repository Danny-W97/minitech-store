#!/usr/bin/env python3
"""A43.38: reviewed internal-copy procedures and source evidence, never runtime contents."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
IDS={'cc-job-output','cc-dispatch-history','cc-ai-settings','application-logs','monitoring-storage','pdf-memory-cache','browser-state','cc-operator-sessions','cc-browser-intents','account-export-chunks','service-keyrings'}
CONFIGS=['compose.apps.yaml','compose.yaml','docker-compose.monitoring.yaml','deploy/k8s/base/deployments.yaml','infra/monitoring/prometheus/prometheus.yml']
def check(root=ROOT):
    root=Path(root);contract=json.loads((root/'scripts/qa/contracts/internal-copies.json').read_text(encoding='utf-8'))
    inventory=json.loads((root/'scripts/qa/contracts/data-lifecycle.json').read_text(encoding='utf-8'));copies={entry['id']:entry for entry in inventory['copies']}
    if contract.get('stage')!='A43.38' or contract.get('fullErasureComplete') is not False or contract.get('retentionActivation')!='A43.40':raise ValueError('Premature internal-copy activation or completion')
    resources=contract.get('resources',{})
    if set(resources)!=IDS or not IDS<=copies.keys():raise ValueError('Missing internal-copy procedure')
    internal={name for name,c in copies.items() if c['kind'] in {'runtime','browser'} and name!='browser-downloads'}
    if internal!=IDS|{'account-privacy-protocol','independent-privacy-journal','assistant-egress-approvals'}:raise ValueError('New internal copy requires a reviewed stage assignment')
    for name,entry in resources.items():
        if not entry.get('procedure') or not entry.get('handling') or not entry.get('remainingLimit'):raise ValueError('Unresolved internal copy: '+name)
        for source in [entry['procedure'],*entry.get('sources',[])]:
            path=Path(source)
            if path.is_absolute() or '..' in path.parts or '\\' in source or not (root/path).is_file():raise ValueError('Missing/unsafe internal-copy source')
    evidence=[];backends=[]
    for name in CONFIGS:
        raw=(root/name).read_bytes();text=raw.decode('utf-8')
        # Detection is deliberately conservative; no host env, daemon config, vault or user logs are opened.
        detected=bool(re.search(r'(?i)\b(loki|fluentd|fluent-bit|promtail|otel_exporter|sentry_dsn|syslog|gelf|splunk)\b','\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('#'))))
        evidence.append({'source':name,'sha256':hashlib.sha256(raw).hexdigest(),'externalLogBackendMentioned':detected})
        if detected:backends.append(name)
    return {'stage':'A43.38','status':'PASS','resourcesReviewed':len(resources),'executedDataOperations':0,'secretValuesRead':False,'runtimeConfigurationInspected':False,'externalLogging':{'repositoryStatus':'REQUIRES_REVIEW' if backends else 'NO_EXPLICIT_CONFIGURATION_IN_REVIEWED_SOURCES','runtimeEvidenceRequired':True},'sourceEvidence':evidence,'fullErasureComplete':False,'fullA43Complete':False}
if __name__=='__main__':print(json.dumps(check(),ensure_ascii=False,indent=2))

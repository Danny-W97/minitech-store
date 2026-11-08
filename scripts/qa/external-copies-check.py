#!/usr/bin/env python3
"""A43.39 source/contract review. No credentials, runtime settings or providers are opened."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
IDS={'assistant-provider-copy','assistant-egress-approvals','mail-payment-providers','browser-downloads','historical-chat-proof','deleted-chat-status'}

def check(root=ROOT):
    root=Path(root).resolve()
    contract=json.loads((root/'scripts/qa/contracts/external-copies.json').read_text(encoding='utf-8'))
    if contract.get('stage')!='A43.39' or contract.get('fullA43Complete') is not False or contract.get('fullErasureComplete') is not False or contract.get('retentionActivation')!='A43.40':raise ValueError('Premature completion or activation')
    resources=contract.get('resources',{})
    if set(resources)!=IDS:raise ValueError('Unreviewed external-copy scope')
    evidence=contract.get('requiredEvidence',[])
    if set(evidence)!={'actual-provider-configuration-and-recipients','provider-specific-request-and-outcome-evidence','authorized-case-process-and-evidence-custody','A05_DEFERRED'}:raise ValueError('Hidden manual evidence dependency')
    allowed={'REQUIRED_MANUAL_EVIDENCE','IMPLEMENTED_BOUNDED_RAM','USER_MANAGED','MANUAL_REVIEW'}
    for row in resources.values():
        if row.get('status') not in allowed or any(not row.get(key) for key in ('owner','handling','remainingLimit','procedure')):raise ValueError('Missing owner/procedure/limitation/status')
    for key in ('assistant-provider-copy','mail-payment-providers'):
        if resources[key]['status']!='REQUIRED_MANUAL_EVIDENCE':raise ValueError('Provider evidence cannot be assumed')
    sources=set(contract.get('sourceFiles',[]))|{row['procedure'] for row in resources.values()}
    if len(sources)<8:raise ValueError('Missing source evidence')
    hashes=[]
    for source in sorted(sources):
        path=Path(source);target=(root/path).resolve()
        if path.is_absolute() or ':' in source or '\\' in source or '..' in path.parts or not target.is_relative_to(root) or not target.is_file():raise ValueError('Missing/unsafe source evidence')
        hashes.append({'source':source,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
    inventory=json.loads((root/'scripts/qa/contracts/data-lifecycle.json').read_text(encoding='utf-8'))
    copies={row['id']:row for row in inventory['copies']}
    if {key for key,row in copies.items() if row['kind']=='external'}!={'assistant-provider-copy','mail-payment-providers'}:
        raise ValueError('New external copy requires an explicit reviewed procedure')
    for key in IDS-{'historical-chat-proof','deleted-chat-status'}:
        if key not in copies or 'A43.39' not in copies[key]['mechanisms']:raise ValueError('Unclassified external/approval copy')
    return {'stage':'A43.39','status':'PASS','resourcesReviewed':len(resources),'sourceEvidence':hashes,
            'requiredManualEvidence':evidence,'executedDataOperations':0,'providerContacts':0,'secretValuesRead':False,
            'runtimeConfigurationInspected':False,'fullA43Complete':False,'fullErasureComplete':False}

if __name__=='__main__':print(json.dumps(check(),ensure_ascii=False,indent=2))

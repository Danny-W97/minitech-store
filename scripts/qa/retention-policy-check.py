#!/usr/bin/env python3
"""Read-only A43.40 policy/configuration assessment. Never applies settings or deletes data."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = 'scripts/qa/contracts/retention-policy.json'
POLICY_VERSION = 'a43.40-2026-11-02.8'
DECISION_RECEIPT = 'scripts/qa/contracts/retention-decisions.json'
# Canonical fingerprint of the reviewed user answers. Updating a plan requires
# a reviewed checker revision too; changing the matrix hash alone is insufficient.
DECISION_RECEIPT_SHA256 = '0710c316783ee62bc827b565dd6c1443d7e0b57a40598b2fd1deeb34d469e959'
spec = importlib.util.spec_from_file_location('retention_classification', Path(__file__).with_name('technical-data-classification-check.py'))
classification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(classification)
commerce_spec = importlib.util.spec_from_file_location('commerce_retention', Path(__file__).with_name('commerce-retention-check.py'))
commerce = importlib.util.module_from_spec(commerce_spec)
commerce_spec.loader.exec_module(commerce)
lifecycle = classification.lifecycle
require = lifecycle.require

# These are the user's decisions for this revision, not defaults inferred from YAML.
APPROVED_DAYS = {'audit': 90, 'recommendation-log': 7, 'invoice-outbox-content': 30,
                 'payment-outbox-contact': 30, 'rejected-inbox-content': 30,
                 'sent-notification-content': 30, 'resolved-dlq-content': 30,
                 'job-logs': 14, 'backup-sets': 14}
PENDING_DECISIONS = {'commercial-periods', 'streams', 'other-copies'}
SECURITY_GUARDS = {'existing-eligibility-and-locks', 'no-new-runtime-activation',
                   'whole-refresh-family-protection', 'active-MFA-and-credentials-protected',
                   'existing-SQL-clocks-and-worker-enablement-unchanged'}
PRESERVED_TTL_FIELDS = {'refresh_tokens', 'password_reset_tokens', 'customer_totp_methods', 'customer_recovery_codes'}
MUTATING_ACTIONS = {'existing-purge', 'existing-compact', 'existing-file-purge', 'existing-whole-copy-purge', 'existing-batched-expiry'}
CONTENT_SCOPES = {
    'invoice-outbox-content': {'billing-service/invoice_outbox_events': {'payload'}},
    'payment-outbox-contact': {'payment-service/payment_outbox_events': {'recipient', 'delivery_address'}},
    'rejected-inbox-content': {owner + '/event_inbox': {'payload', 'error'} for owner in ('billing-service', 'notification-service')},
    'sent-notification-content': {'notification-service/notifications': {'subject', 'message'}},
    'resolved-dlq-content': {'notification-service/notification_dead_letters': {'subject', 'message', 'failure_reason'}},
}
WHOLE_ROW_SCOPES = {'audit': {'customer-service/audit_events'},
                    'recommendation-log': {'recommendation-service/recommendation_requests'},
                    'export-result': {'customer-service/' + name for name in ('account_export_jobs', 'account_export_tasks', 'account_export_chunks')}}
COMMERCIAL_TABLES = {'invoice_corrections', 'invoice_items', 'invoices', 'shipments',
                     'inventory_reservation_items', 'inventory_reservations', 'order_items', 'orders',
                     'payment_refunds', 'payments', 'coupon_reservations', 'promotion_redemptions',
                     'return_events', 'return_lines', 'return_requests'}
COPY_POLICIES = {
    'platform-recovery-bundles': 'backup-sets', 'legacy-database-backups': 'backup-sets',
    'redis-durability': 'keyrings-and-legacy', 'cc-job-output': 'job-logs',
    'cc-dispatch-history': 'keyrings-and-legacy', 'cc-ai-settings': 'keyrings-and-legacy',
    'application-logs': 'log-monitoring-copies', 'monitoring-storage': 'log-monitoring-copies',
    'pdf-memory-cache': 'runtime-limits', 'browser-state': 'device-copies',
    'browser-downloads': 'device-copies', 'assistant-provider-copy': 'provider-copies',
    'mail-payment-providers': 'provider-copies', 'service-keyrings': 'keyrings-and-legacy',
    'cc-operator-sessions': 'runtime-limits', 'account-export-chunks': 'export-result',
    'account-privacy-protocol': 'account-request', 'independent-privacy-journal': 'replay-markers',
    'cc-browser-intents': 'device-copies', 'assistant-egress-approvals': 'runtime-limits',
}


def load(root, name):
    return lifecycle.document(lifecycle.source(root, name))


def decision_receipt(root, doc):
    require(doc.get('decisionReceipt') == DECISION_RECEIPT, 'Missing reviewed decision receipt')
    receipt = load(root, DECISION_RECEIPT)
    canonical = json.dumps(receipt, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')
    require(hashlib.sha256(canonical).hexdigest() == DECISION_RECEIPT_SHA256,
            'Unreviewed user decision change')
    # These fields are also pinned above. Make the boundary explicit in reports:
    # a recorded choice is not an implemented or activated cleanup rule.
    require(receipt['scope'] == 'planning-only-not-runtime-configuration'
            and receipt['activationAllowed'] is False, 'Decision receipt cannot activate runtime')
    return {'receiptVersion': receipt['receiptVersion'], 'entries': len(receipt['decisions']),
            'statuses': {key: value['status'] for key, value in receipt['decisions'].items()},
            'blockers': receipt['activationBlockers'], 'activationAllowed': False}


def controls():
    """Only non-secret, explicitly reviewed example settings. No environment is read."""
    result = {}
    prefixes = {'CUSTOMER_AUDIT': ('audit', 'purge'),
                'BILLING_OUTBOX': ('invoice-outbox-content', 'compact'),
                'PAYMENT_OUTBOX': ('payment-outbox-contact', 'compact'),
                'BILLING_REJECTED_INBOX': ('rejected-inbox-content', 'compact'),
                'NOTIFICATION_REJECTED_INBOX': ('rejected-inbox-content', 'compact'),
                'NOTIFICATION_SENT': ('sent-notification-content', 'compact'),
                'NOTIFICATION_RESOLVED_DLQ': ('resolved-dlq-content', 'compact')}
    for prefix, (policy, active_mode) in prefixes.items():
        result[prefix + '_RETENTION_MODE'] = (policy, 'mode', ('off', 'observe', active_mode))
        result[prefix + '_RETENTION_AGE'] = (policy, 'age', str(APPROVED_DAYS[policy]) + 'd')
    result.update({
        'CC_JOB_LOG_RETENTION_MODE': ('job-logs', 'mode', ('off', 'observe', 'purge')),
        'CC_JOB_LOG_RETENTION_DAYS': ('job-logs', 'days', 14),
        'MINI_ORDER_PLATFORM_BACKUP_RETENTION_MODE': ('backup-sets', 'mode', ('observe', 'purge')),
        'MINI_ORDER_PLATFORM_BACKUP_RETENTION_DAYS': ('backup-sets', 'days', 14),
        'RECOMMENDATION_REQUEST_LOG_RETENTION': ('recommendation-log', 'age', '7d'),
        'CUSTOMER_SECURITY_RETENTION_AGE': ('security-sql', 'age', '7d'),
    })
    return result


def check(root=ROOT):
    root = Path(root).resolve()
    doc = load(root, CONTRACT)
    require(doc.get('schemaVersion') == 1 and doc.get('stage') == 'A43.40', 'Invalid retention policy schema/stage')
    require(doc.get('policyVersion') == POLICY_VERSION, 'Unreviewed policy revision')
    for flag in ('stageComplete', 'runtimeActivation', 'fullA43Complete', 'fullErasureComplete'):
        require(doc.get(flag) is False, 'Premature completion or runtime activation')
    decisions, policies = doc.get('decisions', {}), doc.get('policies', {})
    receipt = decision_receipt(root, doc)
    require(set(decisions) == PENDING_DECISIONS | {'technical-profile', 'replay-markers', 'security-sql', 'previous-decisions', 'existing-behavior'}, 'Missing decision scope')
    for name in PENDING_DECISIONS:
        require(decisions[name].get('status') == 'partial', 'Decision still has unresolved policy or evidence: ' + name)
    require(decisions['security-sql'].get('status') == 'approved'
            and decisions['security-sql'].get('ageDays') == 7, 'Missing approved D03 security period')
    require(decisions['replay-markers'].get('status') == 'approved', 'Missing marker decision')
    require(decisions['technical-profile'].get('status') == 'approved', 'Missing technical profile decision')
    require(decisions['technical-profile'].get('periods') == {name: days * 86400 for name, days in APPROVED_DAYS.items()}, 'Technical periods differ from the explicit decision')
    for name, decision in decisions.items():
        require(decision.get('evidence'), 'Decision lacks evidence: ' + name)
    require(policies, 'Missing policies')
    for name, policy in policies.items():
        require(policy.get('decision') in decisions, 'Unknown policy decision: ' + name)
        for key in ('purpose', 'action', 'guards', 'exceptions', 'copies', 'sources'):
            require(policy.get(key), 'Missing policy ' + key + ': ' + name)
        require(policy.get('copies') == 'independent-copy-policy', 'Copies cannot silently inherit source expiry')
        require(policy.get('privacyRequestHandling') == 'existing-A43.33-A43.39-unchanged', 'Policy cannot replace the existing erasure protocol')
        retention = policy.get('retention', {})
        require(retention.get('startsAt'), 'Missing retention start: ' + name)
        kind, seconds = retention.get('kind'), retention.get('seconds')
        require(kind in {'duration', 'event', 'existing-limits', 'existing-lifecycle', 'conditional-no-ttl', 'unresolved'}, 'Unknown retention kind')
        if kind == 'duration':
            require(type(seconds) is int and seconds > 0, 'Invalid retention interval')
        else:
            require(seconds is None, 'Non-duration policy must not acquire a TTL')
        if policy['decision'] in PENDING_DECISIONS:
            require(kind == 'unresolved' and policy['action'] not in MUTATING_ACTIONS, 'Unresolved policy acquired a deletion rule')
        for source in policy['sources']:
            lifecycle.source(root, source)
    for name, days in APPROVED_DAYS.items():
        require(policies.get(name, {}).get('retention', {}).get('seconds') == days * 86400, 'Approved period drift: ' + name)
    security = policies['security-sql']
    require(security['decision'] == 'security-sql'
            and security['retention'] == {'kind': 'duration', 'seconds': 604800,
                                         'startsAt': 'Existing SQL GREATEST/COALESCE and whole-family eligibility'}
            and security['action'] == 'preserve-current-security-cleanup'
            and SECURITY_GUARDS <= set(security['guards']), 'Approved SQL security scope/guards changed')
    for name in ('replay-markers', 'account-request'):
        require(policies[name]['retention']['kind'] == 'conditional-no-ttl'
                and policies[name]['action'] == 'preserve-no-automatic-expiry', 'Replay/restore markers must not expire by age')
    require(policies['export-result']['retention']['seconds'] == 3600, 'Export result lifetime changed')

    inventory = load(root, lifecycle.CATALOG)
    fields = load(root, classification.CONTRACT)
    actual = classification.schema(root)
    tables = doc.get('tables', {})
    require(set(tables) == set(actual) == set(fields['tables']), 'Missing/new table in retention matrix')
    field_count = 0
    for name, table in tables.items():
        require(table.get('owner') == fields['tables'][name]['owner'], 'Wrong table owner: ' + name)
        columns = table.get('columns', [])
        require(len(columns) == len(set(columns)) and set(columns) == set(actual[name]) == set(fields['tables'][name]['columns']), 'Missing/new retention field: ' + name)
        require(table.get('existingClassificationRules') == sorted(set(fields['tables'][name]['columns'].values())), 'Unreviewed field classification change')
        overrides = table.get('fieldPolicies', {})
        require(set(overrides) <= set(columns), 'Retention override names a missing column')
        require(table.get('defaultPolicy') in policies and set(overrides.values()) <= policies.keys(), 'Unknown table/field policy')
        for column in columns:
            policy_id = overrides.get(column, table['defaultPolicy'])
            if policies[policy_id]['action'] in MUTATING_ACTIONS:
                require(name in WHOLE_ROW_SCOPES.get(policy_id, set())
                        or column in CONTENT_SCOPES.get(policy_id, {}).get(name, set()),
                        'Age-based action escapes the reviewed field scope')
        # Protect both row identity and the token proof's separate lifetime.
        table_name = name.split('/')[1]
        if table_name in {'account_privacy_fences', 'commerce_privacy_locks', 'review_privacy_keys', 'event_identity_aliases'}:
            require(table['defaultPolicy'] == 'replay-markers' and not overrides, 'Identity marker scope changed')
        if name in commerce.STORE_TABLES | commerce.SOURCE_TABLES | commerce.COORDINATOR_TABLES | commerce.ORDER_TABLES | commerce.PEER_TABLES | commerce.HOLD_TABLES:
            require(table['defaultPolicy'] == 'replay-markers' and not overrides, 'Assessment metadata cannot acquire an automatic TTL')
        if table_name in PRESERVED_TTL_FIELDS:
            require(table['defaultPolicy'] == 'security-sql' and not overrides, 'Reviewed SQL security scope was bypassed')
        if table_name in COMMERCIAL_TABLES:
            require(table['defaultPolicy'] == 'commercial-records' and not overrides, 'Commercial field/dependency mapping is not implemented')
        field_count += len(columns)
    for key, rows in (('redis', inventory['redis']['stores']), ('copies', inventory['copies'])):
        reviewed = doc.get(key, {})
        require(set(reviewed) == {row['id'] for row in rows}, 'Missing/new retention ' + key)
        for row in rows:
            entry = reviewed[row['id']]
            require(entry.get('owners') == row['owners'] and entry.get('policy') in policies, 'Missing owner/policy for ' + row['id'])
            require(entry.get('runtimeEvidence') == 'pending', 'Actual environment evidence cannot be inferred from source review')
            if key == 'redis':
                require(entry.get('patterns') == row['patterns'], 'Unreviewed Redis namespace')
            else:
                require(entry.get('kind') == row['kind'], 'Copy kind drift')
                require(entry['policy'] == COPY_POLICIES.get(row['id']), 'Copy policy escapes the explicit reviewed scope')
    for stream in ('payment-stream', 'invoice-stream'):
        require(doc['redis'][stream]['policy'] == stream and policies[stream]['retention']['kind'] == 'unresolved', 'Stream runtime review/consumerless design remains unresolved')
    require(doc['copies']['redis-durability']['policy'] != 'backup-sets', 'Active Redis/AOF cannot acquire backup TTL')
    require(doc['copies']['independent-privacy-journal']['policy'] == 'replay-markers', 'Independent journal cannot acquire backup TTL')

    hashes = doc.get('sourceHashes', {})
    required_sources = {lifecycle.CATALOG, classification.CONTRACT, 'scripts/qa/contracts/external-copies.json', DECISION_RECEIPT,
                        commerce.CONTRACT, 'scripts/qa/commerce-retention-check.py'}
    for policy in policies.values():
        required_sources.update(policy['sources'])
    require(required_sources <= hashes.keys(), 'Missing reviewed source fingerprint')
    for source, expected in hashes.items():
        require(hashlib.sha256(lifecycle.source(root, source).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected, 'Unreviewed retention source change: ' + source)
    require(doc.get('environmentEvidence') == {'dev': 'pending', 'test': 'pending', 'prod': 'pending'}, 'Runtime evidence has not been supplied')
    commerce_plan = commerce.check(root, actual)
    require(len(doc.get('activationPrerequisites', [])) >= 8, 'Missing activation prerequisites')
    lifecycle.source(root, doc.get('stopProcedure'))
    blocked = sorted('policy:' + name for name, p in policies.items() if p['retention']['kind'] == 'unresolved')
    blocked += ['environment:' + key for key in doc['environmentEvidence']]
    blocked += ['evidence:' + key for key in doc['activationPrerequisites']]
    blocked += ['decision-plan:' + key for key in receipt['blockers']]
    blocked += ['commerce:' + key for key in commerce_plan['blockers']]
    return {'stage': 'A43.40', 'status': 'PASS', 'policyVersion': doc['policyVersion'],
            'policySha256': hashlib.sha256(lifecycle.source(root, CONTRACT).read_bytes().replace(b'\r\n', b'\n')).hexdigest(),
            'tables': len(tables), 'columns': field_count, 'redisStores': len(doc['redis']), 'copyStores': len(doc['copies']),
            'policies': len(policies), 'decisionReceipt': receipt, 'commerceAssessment': commerce_plan,
            'policyReady': False, 'stageComplete': False, 'activationAllowed': False,
            'blockers': blocked, 'executedDataOperations': 0, 'runtimeConfigurationInspected': False,
            'secretValuesRead': False, 'fullA43Complete': False, 'fullErasureComplete': False}


def candidate(root, doc, result):
    require(set(doc) == {'schemaVersion', 'policyVersion', 'policySha256', 'environment', 'intent', 'settings'}, 'Invalid candidate shape')
    require(doc['schemaVersion'] == 1 and doc['policyVersion'] == result['policyVersion'] and doc['policySha256'] == result['policySha256'], 'Candidate belongs to another policy revision')
    require(doc['environment'] in {'qa', 'dev', 'test', 'prod'} and doc['intent'] in {'observe', 'activate'}, 'Invalid candidate environment/intent')
    require(isinstance(doc['settings'], dict) and doc['settings'], 'Missing candidate settings')
    rules = controls()
    for key, value in doc['settings'].items():
        require(key in rules, 'Unknown/non-public candidate setting')
        _, kind, expected = rules[key]
        if kind == 'mode':
            require(value in expected, 'Invalid candidate mode')
            require(doc['intent'] == 'activate' or value in {'off', 'observe'}, 'Observe candidate contains a destructive mode')
        elif kind == 'days':
            require(type(value) is int and value == expected, 'Candidate period differs from the decision')
        else:
            require(type(value) is str and value == expected, 'Candidate period differs from the decision')
    require(doc['intent'] != 'activate', 'A43.40 is unresolved; this read-only checker cannot authorize activation')
    return {'status': 'PASS', 'environment': doc['environment'], 'settingsReviewed': len(doc['settings']),
            'coverage': 'only the listed public settings; not a full environment validation',
            'applied': False, 'activationAllowed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--require-ready', action='store_true')
    parser.add_argument('--candidate', help='Workspace-relative public JSON candidate, never SOPS or runtime environment')
    parser.add_argument('--output', help='New workspace-relative JSON report')
    args = parser.parse_args()
    try:
        result = check(args.root)
        if args.candidate:
            result['candidate'] = candidate(args.root, load(args.root, args.candidate), result)
        if args.require_ready and not result['policyReady']:
            result['status'] = 'NOT_READY'
        if args.output:
            path = Path(args.output)
            require(not path.is_absolute() and ':' not in str(path) and '..' not in path.parts, 'Unsafe report destination')
            target = (args.root / path).resolve()
            require(target.is_relative_to(args.root.resolve()) and not target.exists(), 'Existing/unsafe report destination')
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('x', encoding='utf-8') as stream:
                json.dump(result, stream, ensure_ascii=False, indent=2)
                stream.write('\n')
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['status'] == 'PASS' else 2
    except (ValueError, OSError, KeyError, TypeError, AttributeError):
        # Candidate files may be malformed/misaddressed; never echo their contents or values.
        print(json.dumps({'stage': 'A43.40', 'status': 'FAILED', 'code': 'RETENTION_POLICY_REJECTED', 'activationAllowed': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

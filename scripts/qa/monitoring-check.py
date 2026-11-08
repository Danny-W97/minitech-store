#!/usr/bin/env python3
"""Validate the shipped monitoring configs and real PromQL transitions without app secrets."""
import json
from pathlib import Path
import secrets
import subprocess
import yaml

ROOT = Path(__file__).resolve().parents[2]


def command(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()


def stream_metric_tests():
    """Exercise the actual dashboard queries, including missing/unknown and shared replicas."""
    dashboard = json.loads((ROOT / 'infra/monitoring/grafana/dashboards/business-alerts.json').read_text(encoding='utf-8'))
    panels = {p.get('id'): p for p in dashboard['panels']}
    queries = {name: panels[panel]['targets'][0]['expr'] for name, panel in [('pending', 1551), ('lag', 1552)]}
    result = []
    # Every row is a separate promtool test. No new alert or business/SLO threshold.
    cases = [
        ('fresh replicas use maximum, not sum', [(1, 1, 1, 1, 3, 8), (2, 1, 1, 1, 1, 2)], False, 3, 8),
        ('failed collection is not zero', [(1, 0, 1, 1, 0, 0)], False, None, None),
        ('missing group is not zero', [(1, 1, 0, 0, 0, 0)], False, None, None),
        ('stale successful collection is excluded', [(1, 1, 1, 1, 3, 8)], True, None, None),
        ('future timestamp is not fresh', [(1, 1, 1, 1, 3, 8)], 'future', None, None),
        ('unknown lag preserves known pending', [(1, 1, 1, 0, 2, 0)], False, 2, None),
        ('known empty group is zero', [(1, 1, 1, 1, 0, 0)], False, 0, 0),
        ('missing series are not replaced with zero', [], False, None, None),
        ('failed replica cannot override a healthy observation', [(1, 0, 1, 1, 99, 99), (2, 1, 1, 1, 2, 5)], False, 2, 5),
    ]
    for name, replicas, stale, pending, lag in cases:
        series = []
        for instance, up, present, known, count, unread in replicas:
            labels = '{application="billing-service",instance="fixture-' + str(instance) + '",stream="payments",recipient="billing"}'
            for metric, value in [('pending', count), ('lag', unread), ('collector_up', up), ('group_present', present), ('lag_known', known)]:
                series.append({'series': 'platform_redis_stream_' + metric + labels, 'values': str(value) + 'x8'})
            series.append({'series': 'platform_redis_stream_collector_last_success_timestamp_seconds' + labels,
                           'values': '900x8' if stale == 'future' else ('0x8' if stale else '0+60x8')})
        expressions = []
        for metric, value in [('pending', pending), ('lag', lag)]:
            expected = [] if value is None else [{'labels': '{recipient="billing",stream="payments"}', 'value': value}]
            expressions.append({'expr': queries[metric], 'eval_time': '5m', 'exp_samples': expected})
        result.append({'name': 'Redis dashboard: ' + name, 'interval': '1m', 'input_series': series, 'promql_expr_test': expressions})
    return result


def validate(directory=None):
    directory = directory or ROOT / 'tmp' / ('monitoring-check-' + secrets.token_hex(4))
    directory.mkdir(parents=True, exist_ok=True)
    monitoring = ROOT / 'infra/monitoring'
    compose = yaml.safe_load((ROOT / 'docker-compose.monitoring.yaml').read_text())
    prometheus = compose['services']['prometheus']['image']
    alertmanager = compose['services']['alertmanager']['image']
    config = yaml.safe_load((monitoring / 'prometheus/prometheus.yml').read_text())
    inventory = json.loads((ROOT / 'deploy/components.json').read_text())
    # Support only the canonical service inventory, avoiding an unnoticed scrape gap.
    components = inventory['components'] if isinstance(inventory, dict) else inventory
    expected = {c['name'] + ':8080' for c in components if c['runtime'] == 'jvm'}
    actual = {target for job in config['scrape_configs'] if job['job_name'] == 'mini-order-platform'
              for group in job['static_configs'] for target in group['targets']}
    assert actual == expected, (actual, expected)
    rules = yaml.safe_load((monitoring / 'prometheus/rules/business.yml').read_text())['groups'][0]['rules']
    names = {rule['alert'] for rule in rules}
    runbook = (ROOT / 'docs/A35_ALERTY_BIZNESOWE.md').read_text()
    for rule in rules:
        assert rule['annotations']['runbook'].split('#')[1] in runbook
    # A failing sample, a transient that must not fire, and a recovery for each rule.
    examples = {
        'BusinessQueueStalled': [('platform_business_queue_oldest_age_seconds{queue="order_saga"}', 900, 0)],
        'BusinessQueueBacklog': [('platform_business_queue_count{queue="payment_outbox"}', 1001, 0)],
        'BusinessDeadLetter': [('platform_business_queue_count{queue="notification_dlq"}', 1, 0)],
        'PaidWithoutInvoice': [('platform_business_queue_oldest_age_seconds{queue="paid_without_invoice"}', 901, 0)],
        'BusinessCollectorUnavailable': [('platform_business_collector_up{queue="order_saga"}', 0, 1)],
        'BusinessMetricsMissing': [('up{job="mini-order-platform",instance="payment-service:8080"}', 1, 0)],
        'PlatformTargetDown': [('up{job="mini-order-platform",instance="order-service:8080"}', 0, 1)],
        'DatabasePoolExhausted': [('hikaricp_connections_active{pool="db"}', 4, 0), ('hikaricp_connections_max{pool="db"}', 4, 4), ('hikaricp_connections_pending{pool="db"}', 1, 0)],
        'PlatformHttpErrors': [('http_server_requests_seconds_count{job="mini-order-platform",application="order-service",status="500"}', '0+10x12', '120+0x12')],
        'AlertDeliveryFailed': [('alertmanager_notifications_failed_total{integration="webhook"}', '0+1x12', '12+0x12')],
        'BusinessQueueSloBreach': [('platform_business_queue_oldest_age_seconds{queue="payment_refund"}', 3601, 0)],
        'RedisStreamLagHigh': [('platform_redis_stream_lag{recipient="billing",stream="payments"}', 101, 0)],
        'Watchdog': [],
    }
    assert set(examples) == names
    tests = []
    for rule in rules:
        name = rule['alert']
        if name == 'Watchdog':
            expression = 'ALERTS{alertname="Watchdog",alertstate="firing"}'
            labels = 'alertname="Watchdog",alertstate="firing",severity="none"'
            tests.append({'name': 'Watchdog fires continuously', 'interval': '1m', 'input_series': [],
                          'promql_expr_test': [
                              {'expr': expression, 'eval_time': '0m', 'exp_samples': [{'labels': 'ALERTS{' + labels + '}', 'value': 1}]},
                              {'expr': expression, 'eval_time': '10m', 'exp_samples': [{'labels': 'ALERTS{' + labels + '}', 'value': 1}]},
                              {'expr': expression, 'eval_time': '25m', 'exp_samples': [{'labels': 'ALERTS{' + labels + '}', 'value': 1}]}]})
            continue
        series = []
        for metric, bad, good in examples[name]:
            bad_values = bad if isinstance(bad, str) else str(bad) + 'x12'
            good_values = good if isinstance(good, str) else str(good) + 'x12'
            series.append({'series': metric, 'values': bad_values + ' ' + good_values})
        # Separate PromQL ALERTS checks avoid duplicating rendered label/annotation templates.
        expression = 'ALERTS{alertname="' + name + '",alertstate="firing"}'
        labels = {'alertname': name, 'alertstate': 'firing', 'severity': rule['labels']['severity']}
        first_metric = examples[name][0][0]
        if 'queue=' in first_metric: labels['queue'] = first_metric.split('queue="')[1].split('"')[0]
        if name in ('BusinessMetricsMissing', 'PlatformTargetDown'):
            labels.update(job='mini-order-platform', instance=first_metric.split('instance="')[1].split('"')[0])
        if name == 'DatabasePoolExhausted': labels['pool'] = 'db'
        if name == 'PlatformHttpErrors': labels.update(application='order-service')  # aggregation keeps no instance when absent
        if name == 'AlertDeliveryFailed': labels['integration'] = 'webhook'
        if name == 'RedisStreamLagHigh': labels.update(recipient='billing', stream='payments')
        rendered_labels = ','.join(k + '=' + json.dumps(v) for k, v in sorted(labels.items()))
        tests.append({'name': name + ' fires and resolves', 'interval': '1m', 'input_series': series,
                      'promql_expr_test': [
                          {'expr': expression, 'eval_time': '0m', 'exp_samples': []},
                          {'expr': expression, 'eval_time': str(max(0, int(rule['for'].removesuffix('m')) - 1)) + 'm', 'exp_samples': []},
                          {'expr': expression, 'eval_time': '10m', 'exp_samples': [{'labels': 'ALERTS{' + rendered_labels + '}', 'value': 1}]},
                          {'expr': expression, 'eval_time': '25m', 'exp_samples': []}]})
    # Freshness failures are independent of the collector's last success flag.
    tests.append({'name': 'stale collector is detected', 'interval': '1m', 'input_series': [
        {'series': 'platform_business_collector_last_success_timestamp_seconds{queue="order_saga"}', 'values': '0x12'}],
        'promql_expr_test': [{'expr': 'count(ALERTS{alertname="BusinessCollectorUnavailable",alertstate="firing"})', 'eval_time': '10m', 'exp_samples': [{'labels': '{}', 'value': 1}]}]})
    tests.append({'name': 'one missing payment queue is detected', 'interval': '1m', 'input_series': [
        {'series': 'up{job="mini-order-platform",instance="payment-service:8080"}', 'values': '1x12'},
        *[{'series': 'platform_business_collector_up{instance="payment-service:8080",queue="' + queue + '"}', 'values': '1x12'} for queue in ['payment_outbox', 'payment_follow_up', 'payment_follow_up_dlq', 'paid_without_invoice', 'payment_refund']]],
        'promql_expr_test': [{'expr': 'count(ALERTS{alertname="BusinessMetricsMissing",alertstate="firing"})', 'eval_time': '10m', 'exp_samples': [{'labels': '{}', 'value': 1}]}]})
    rule_scenarios = len(tests)
    stream_tests = stream_metric_tests()
    tests.extend(stream_tests)
    test_file = directory / 'rules.test.yml'
    test_file.write_text(yaml.safe_dump({'rule_files': ['/etc/prometheus/rules/business.yml'], 'evaluation_interval': '1m', 'tests': tests}))
    base = ['docker', 'run', '--rm', '--network', 'none', '-v', str(monitoring / 'prometheus') + ':/etc/prometheus:ro', '-v', str(directory.resolve()) + ':/test:ro', '--entrypoint', '/bin/promtool', prometheus]
    outputs = [command(*base, 'check', 'config', '/etc/prometheus/prometheus.yml'),
               command(*base, 'test', 'rules', '/test/rules.test.yml'),
               command('docker', 'run', '--rm', '--network', 'none', '-v', str(monitoring / 'alertmanager') + ':/etc/alertmanager:ro', '--entrypoint', '/bin/amtool', alertmanager, 'check-config', '/etc/alertmanager/alertmanager.yml')]
    (directory / 'validation.log').write_text('\n'.join(outputs))
    print('PASS monitoring: configs, 15 targets, 13 alert scenarios (including Watchdog heartbeat), stale collector, partial metric loss and 9 Redis dashboard scenarios', flush=True)
    return {'ruleScenarios': rule_scenarios, 'streamMetricScenarios': len(stream_tests), 'totalScenarios': len(tests),
            'scrapeTargets': len(actual), 'prometheusImage': prometheus, 'alertmanagerImage': alertmanager}


if __name__ == '__main__':
    try:
        validate()
    except subprocess.CalledProcessError as error:
        print(error.stdout, error.stderr)
        raise

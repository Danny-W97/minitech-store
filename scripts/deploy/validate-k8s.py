#!/usr/bin/env python3
"""Render and strictly validate application manifests without contacting a cluster."""
import argparse
import ipaddress
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import yaml
from components import ROOT, load

NAME = 'app.kubernetes.io/name'
PART_OF = {'app.kubernetes.io/part-of': 'mini-order-platform'}
APPLICATION = {**PART_OF, 'app.kubernetes.io/component': 'application'}


class StrictLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError('Duplicate YAML key: ' + str(key))
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


StrictLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def documents(text):
    return [item for item in yaml.load_all(text, Loader=StrictLoader) if item is not None]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_inventory(root=ROOT):
    inventory = load(root)
    components = inventory['components']
    names = {item['name'] for item in components}
    modules = {node.text for node in ET.parse(Path(root) / 'pom.xml').findall('.//{*}modules/{*}module')}
    modules -= {'platform-security', 'qa-tests'}
    require(modules == {item['name'] for item in components if item['runtime'] == 'jvm'}, 'Maven modules and deployment inventory disagree')
    compose = yaml.safe_load((Path(root) / 'compose.apps.yaml').read_text())
    require(set(compose['services']) == names, 'Compose services and deployment inventory disagree')
    require(len(names) == 16 and sum(bool(item['database']) for item in components) == 14, 'Expected 16 application components and 14 database bindings')
    for item in components:
        if item['runtime'] != 'jvm': continue
        module = Path(root) / item['name']
        sources = list((module / 'src/main/java').rglob('*.java')) + list((module / 'src/main/resources').glob('application*.yaml'))
        source = '\n'.join(path.read_text(encoding='utf-8') for path in sources)
        dependencies = {name.lower().replace('_', '-') + '-service' for name in re.findall(r'\$\{([A-Z][A-Z_]*)_SERVICE_URL(?=[:}])', source)}
        require(dependencies <= set(item['http']), 'HTTP client missing from network inventory: ' + item['name'])
        broker = 'spring-boot-starter-data-redis' in (module / 'pom.xml').read_text()
        require(broker == item['redis'], 'Redis client missing from network inventory: ' + item['name'])
    return inventory


def pod_pairs(rules, direction):
    pairs = set()
    for rule in rules:
        require(rule.get(direction) and rule.get('ports'), 'Broad network rule without peers or ports')
        for peer in rule[direction]:
            if 'ipBlock' in peer or 'namespaceSelector' in peer:
                continue
            selector = peer.get('podSelector', {}).get('matchLabels', {})
            require(set(selector) == {NAME}, 'Broad application pod selector')
            for port in rule['ports']:
                require(port.get('protocol') == 'TCP' and type(port.get('port')) is int, 'Unexpected application protocol or port')
                pairs.add((selector[NAME], port['port']))
    return pairs


def check_network(policies, components):
    expected_names = {item['name'] for item in components} | {'application-default-deny', 'application-dns'}
    require(set(policies) == expected_names, 'NetworkPolicy inventory does not cover exactly the application')
    deny = policies['application-default-deny']['spec']
    require(deny['podSelector'] == {'matchLabels': APPLICATION} and set(deny['policyTypes']) == {'Ingress', 'Egress'}
            and deny.get('ingress') == [] and deny.get('egress') == [], 'Application default deny is missing')
    dns = policies['application-dns']['spec']
    require(dns['podSelector'] == {'matchLabels': APPLICATION} and dns.get('policyTypes') == ['Egress'] and not dns.get('ingress') and dns.get('egress') == [{
        'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'kube-system'}}}],
        'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]}], 'DNS policy is too broad or incomplete')
    for item in components:
        spec = policies[item['name']]['spec']
        require(spec['podSelector'] == {'matchLabels': {NAME: item['name']}}
                and set(spec['policyTypes']) == {'Ingress', 'Egress'}, 'Incorrect policy target: ' + item['name'])
        expected = {(name, 8080) for name in item['http']}
        if item['database']:
            expected.add((item['database'] + '-postgres', 5432))
        if item['redis']:
            expected.add(('event-broker', 6379))
        require(all(set(peer) in ({'podSelector'}, {'ipBlock'}) for rule in spec['egress'] for peer in rule['to']),
                'Cross-namespace or broad application egress: ' + item['name'])
        require(pod_pairs(spec['egress'], 'to') == expected, 'Egress dependency mismatch: ' + item['name'])
        incoming = {(source['name'], 8080) for source in components if item['name'] in source['http']}
        require(pod_pairs(spec['ingress'], 'from') == incoming, 'Ingress dependency mismatch: ' + item['name'])
        public = [rule for rule in spec['egress'] if any('ipBlock' in peer for peer in rule['to'])]
        require({port['port'] for rule in public for port in rule['ports']} == set(item.get('publicTcpPorts', [])),
                'Unexpected public egress: ' + item['name'])
        for rule in public:
            require(rule['to'] == [
                {'ipBlock': {'cidr': '0.0.0.0/0', 'except': ['10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8', '169.254.0.0/16']}},
                {'ipBlock': {'cidr': '::/0', 'except': ['fc00::/7', 'fe80::/10', '::1/128']}}]
                and all(port.get('protocol') == 'TCP' for port in rule['ports']), 'Public egress must exclude private network ranges')
        external = [peer for rule in spec['ingress'] for peer in rule['from'] if 'namespaceSelector' in peer]
        allowed_external = [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'ingress-nginx'}},
                             'podSelector': {'matchLabels': {'app.kubernetes.io/component': 'controller'}}}] if item['name'] == 'frontend' else []
        require(external == allowed_external, 'Unexpected external ingress: ' + item['name'])


def check_documents(items, inventory, root=ROOT, release=False):
    seen = set(); grouped = {}
    for item in items:
        require(item['kind'] in {'Namespace', 'ConfigMap', 'Deployment', 'Service', 'NetworkPolicy', 'Ingress'},
                'Unexpected resource or inline Secret; provision infrastructure and secrets separately')
        key = (item['kind'], item['metadata'].get('namespace', ''), item['metadata']['name'])
        require(key not in seen, 'Duplicate rendered resource: ' + str(key)); seen.add(key)
        grouped.setdefault(item['kind'], {})[item['metadata']['name']] = item
        require(item['kind'] == 'Namespace' or item['metadata'].get('namespace') == 'minitech-store', 'Unexpected namespace: ' + str(key))
    components = inventory['components']; names = {item['name'] for item in components}
    workloads = grouped.get('Deployment', {}); services = grouped.get('Service', {})
    require(set(workloads) == names and set(services) == names, 'Missing or extra application Deployments/Services')
    config = grouped['ConfigMap']['minitech-store-config']['data']
    keys = documents((Path(root) / 'deploy/k8s/base/secret.example.yaml').read_text())[0]['stringData']
    require(not set(config).intersection(keys), 'Secret fields cannot be put in the shared ConfigMap')
    for item in components:
        workload = workloads[item['name']]; template = workload['spec']['template']; pod = template['spec']
        require(template['metadata']['labels'].get(NAME) == item['name'], 'Incorrect pod label')
        require(all(template['metadata']['labels'].get(key) == value for key, value in APPLICATION.items()), 'Missing application network isolation label')
        require(services[item['name']]['spec']['selector'] == {NAME: item['name']}, 'Incorrect Service selector')
        require(pod.get('automountServiceAccountToken') is False, 'Automatic Kubernetes credential mount')
        context = pod.get('securityContext', {})
        require(context.get('runAsNonRoot') is True and context.get('runAsUser', 0) > 0
                and context.get('seccompProfile', {}).get('type') == 'RuntimeDefault', 'Missing pod restrictions')
        require(len(pod['containers']) == 1, 'Unexpected sidecars in the application template')
        container = pod['containers'][0]; context = container.get('securityContext', {})
        require(context.get('allowPrivilegeEscalation') is False and context.get('readOnlyRootFilesystem') is True
                and context.get('capabilities') == {'drop': ['ALL']} and not context.get('privileged')
                and not any(pod.get(key) for key in ('hostNetwork', 'hostPID', 'hostIPC')), 'Missing container restrictions')
        require(all(container.get(name) for name in ('resources', 'startupProbe', 'readinessProbe', 'livenessProbe')), 'Missing resources or probes')
        require(container['resources'].get('requests', {}).get('memory') and container['resources'].get('limits', {}).get('memory'), 'Missing memory budget')
        require(container.get('volumeMounts') == [{'name': 'tmp', 'mountPath': '/tmp'}]
                and pod.get('volumes') == [{'name': 'tmp', 'emptyDir': {'sizeLimit': '256Mi'}}], 'Missing bounded writable temporary directory')
        require('/' + item['name'] + ':' in container['image'], 'Wrong component image: ' + item['name'])
        for source in container.get('envFrom', []):
            require(source == {'configMapRef': {'name': 'minitech-store-config'}}, 'Unexpected shared secret or configuration source')
        env = {entry['name']: entry for entry in container.get('env', [])}
        require(len(env) == len(container.get('env', [])), 'Duplicate environment binding: ' + item['name'])
        allowed_secrets = set()
        if item['database']:
            prefix = item['database'].upper()
            allowed_secrets |= {prefix + '_DB_PASSWORD', 'PLATFORM_' + prefix + '_SERVICE_TOKEN'}
        if item['name'] == 'customer-service':
            allowed_secrets |= {key for key in keys if key.startswith('CUSTOMER_')} | {'PLATFORM_OPERATOR_TOKEN', 'PLATFORM_SERVICE_CREDENTIALS_JSON'}
        if item['name'] == 'payment-service': allowed_secrets |= {'STRIPE_SECRET_KEY', 'STRIPE_WEBHOOK_SECRET'}
        if item['name'] == 'notification-service': allowed_secrets |= {'MAIL_USERNAME', 'MAIL_PASSWORD'}
        for entry in env.values():
            require(set(entry) == {'name', 'valueFrom'}, 'Literal environment values are not allowed; use ConfigMap or Secret references')
            value = entry.get('valueFrom', {})
            require(len(value) == 1 and set(value) <= {'configMapKeyRef', 'secretKeyRef'}, 'Unknown environment source')
            if 'configMapKeyRef' in value:
                ref = value['configMapKeyRef']; require(ref['name'] == 'minitech-store-config' and ref['key'] in config, 'Missing ConfigMap binding: ' + entry['name'])
            if 'secretKeyRef' in value:
                ref = value['secretKeyRef']; require(ref['name'] == 'minitech-store-secrets' and ref['key'] in keys, 'Missing secret example key: ' + entry['name'])
                require(ref['key'] in allowed_secrets, 'Secret assigned to the wrong service: ' + item['name'])
        if item['database']:
            prefix = item['database'].upper()
            for target, key in [('DB_URL', prefix + '_DB_URL'), ('DB_USERNAME', prefix + '_DB_USERNAME'), ('DB_PASSWORD', prefix + '_DB_PASSWORD'),
                                ('PLATFORM_SERVICE_TOKEN', 'PLATFORM_' + prefix + '_SERVICE_TOKEN')]:
                require(target in env and next(iter(env[target]['valueFrom'].values()))['key'] == key, 'Incorrect database or service identity binding: ' + item['name'])
            require(config.get(prefix + '_SERVICE_URL') == 'http://' + item['name'] + ':8080', 'Missing service URL: ' + item['name'])
            require(config[prefix + '_DB_URL'].startswith('jdbc:postgresql://'), 'Invalid PostgreSQL URL: ' + item['name'])
    require(config.get('SPRING_PROFILES_ACTIVE') == 'prod' and config.get('NOTIFICATION_DELIVERY_PROVIDER') == 'smtp', 'Unsupported production profile or notification provider')
    require(config.get('MAIL_HOST') and config.get('MAIL_PORT') == '587', 'Missing SMTP settings')
    namespace = grouped['Namespace']['minitech-store']['metadata']['labels']
    require(namespace.get('pod-security.kubernetes.io/enforce') == 'restricted', 'Restricted pod admission is not enabled')
    check_network(grouped.get('NetworkPolicy', {}), components)
    if release:
        require(config.get('PAYMENT_PROVIDER') == 'stripe', 'Production payment acceptance remains unfinished (A05)')
        require(all(not re.search(r'replace[-_]|minitech\.example|localhost|127\.0\.0\.1', str(value), re.I) for value in config.values()), 'Unconfigured production placeholder')
        require(config.get('GATEWAY_TRUSTED_PROXY_CIDRS'), 'Production proxy trust must be configured for the target ingress')
        require(all(ipaddress.ip_network(value.strip()).prefixlen > 0 for value in config['GATEWAY_TRUSTED_PROXY_CIDRS'].split(',')), 'Production proxy trust cannot cover every address')
        for ingress in grouped.get('Ingress', {}).values():
            hosts = {rule['host'] for rule in ingress['spec']['rules']}
            tls_hosts = {host for entry in ingress['spec'].get('tls', []) for host in entry['hosts']}
            require(hosts and hosts == tls_hosts and all('example' not in host and 'replace' not in host for host in hosts), 'Unconfigured production ingress/TLS hosts')
        require(all(re.search(r':[0-9a-f]{40}$', pod['spec']['template']['spec']['containers'][0]['image']) for pod in workloads.values()), 'Release images must use the build commit tag')
        require(all('replace-me' not in pod['spec']['template']['spec']['containers'][0]['image'] for pod in workloads.values()), 'Unconfigured image registry')
    return {'deployments': len(workloads), 'services': len(services), 'databaseBindings': sum(bool(item['database']) for item in components),
            'networkPolicies': len(grouped['NetworkPolicy'])}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--overlay', default='deploy/k8s/overlays/prod')
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--image-prefix'); parser.add_argument('--image-tag')
    parser.add_argument('--release', action='store_true')
    args = parser.parse_args()
    try:
        for tool in ('kubectl', 'kubeconform'):
            require(shutil.which(tool), 'Missing ' + tool + '; run python3 scripts/deploy/install-validation-tools.py and add its bin directory to PATH')
        subprocess.run([sys.executable, str(ROOT / 'scripts/deploy/generate-k8s.py'), '--check'], check=True, capture_output=True, text=True)
        inventory = check_inventory()
        rendered = subprocess.run(['kubectl', 'kustomize', str(ROOT / args.overlay)], check=True, capture_output=True, text=True).stdout
        items = documents(rendered)
        require(bool(args.image_prefix) == bool(args.image_tag), 'Image prefix and tag must be provided together')
        if args.image_prefix:
            require(re.fullmatch(r'[a-z0-9][a-z0-9./:_-]+', args.image_prefix) and re.fullmatch(r'[0-9a-f]{40}', args.image_tag), 'Invalid image registry prefix or commit tag')
            for item in items:
                if item['kind'] == 'Deployment':
                    item['spec']['template']['spec']['containers'][0]['image'] = f"{args.image_prefix}/{item['metadata']['name']}:{args.image_tag}"
        counts = check_documents(items, inventory, release=args.release)
        output = args.output_dir or ROOT / 'tmp' / ('k8s-validation-' + uuid.uuid4().hex[:8])
        output.mkdir(parents=True, exist_ok=True)
        artifact = output / 'rendered.yaml'
        artifact.write_text(yaml.safe_dump_all(items, sort_keys=False), encoding='utf-8')
        cache = ROOT / 'gitIgnoreOwn/tools/deploy/schema-cache'; cache.mkdir(parents=True, exist_ok=True)
        schema = subprocess.run(['kubeconform', '-strict', '-summary', '-output', 'json', '-kubernetes-version', inventory['kubernetesVersion'],
                                 '-cache', str(cache), str(artifact)], capture_output=True, text=True)
        (output / 'schema.json').write_text(schema.stdout, encoding='utf-8')
        require(schema.returncode == 0, 'Strict Kubernetes schema validation failed: ' + schema.stdout[-5000:] + schema.stderr[-1000:])
        report = {'status': 'PASS', 'scope': 'release configuration' if args.release else 'templates only; preproduction acceptance remains open',
                  'kubernetesSchemaVersion': inventory['kubernetesVersion'], **counts, 'schema': json.loads(schema.stdout)}
        (output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps({'report': str(output / 'report.json'), **counts}, indent=2))
        return 0
    except (ValueError, KeyError, TypeError, OSError, yaml.YAMLError, subprocess.CalledProcessError) as error:
        print('FAIL:', str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

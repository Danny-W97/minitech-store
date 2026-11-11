import copy
import unittest
from test_platform_credentials import ROOT, load

generator = load('deployment_generator', 'scripts/deploy/generate-k8s.py')
validator = load('deployment_validator', 'scripts/deploy/validate-k8s.py')


def fixture():
    result = []
    for name, text in generator.outputs().items():
        if '/base/' in name:
            result.extend(validator.documents(text))
    for name in ('deploy/k8s/base/configmap.yaml', 'deploy/k8s/base/namespace.yaml', 'deploy/k8s/overlays/prod/ingress.yaml'):
        result.extend(validator.documents((ROOT / name).read_text()))
    for item in result:
        if item['kind'] != 'Namespace': item['metadata']['namespace'] = 'minitech-store'
    return result


class KubernetesTemplateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inventory = validator.check_inventory()
        cls.baseline = fixture()

    def setUp(self):
        self.items = copy.deepcopy(self.baseline)

    def resource(self, kind, name):
        return next(item for item in self.items if item['kind'] == kind and item['metadata']['name'] == name)

    def test_complete_inventory_matches_modules_compose_and_all_bindings(self):
        result = validator.check_documents(self.items, self.inventory)
        self.assertEqual({'deployments': 16, 'services': 16, 'databaseBindings': 14, 'networkPolicies': 18}, result)

    def test_finance_quorum_peers_are_reachable_in_both_deployment_formats(self):
        import re,yaml
        source=(ROOT/'order-service/src/main/java/org/demo/orderservice/privacy/FinancePrivacyJobs.java').read_text()
        peers=set(re.findall(r'"([a-z-]+-service)"',source.split('PEERS=List.of(',1)[1].split(');',1)[0]))
        calls={'order-service':peers,'payment-service':{'billing-service','notification-service'},'billing-service':{'payment-service'},'notification-service':{'payment-service'}}
        compose=yaml.safe_load((ROOT/'compose.apps.yaml').read_text())['services'];inventory={item['name']:item for item in self.inventory['components']}
        config=self.resource('ConfigMap','minitech-store-config')['data']
        for caller,targets in calls.items():
            for target in targets:
                with self.subTest(caller=caller,target=target):
                    key=target.removesuffix('-service').upper()+'_SERVICE_URL'
                    self.assertIn(target,inventory[caller]['http'])
                    self.assertEqual('http://'+target+':8080',compose[caller]['environment'][key])
                    self.assertEqual('http://'+target+':8080',config[key])

    def test_missing_each_previously_omitted_component_is_rejected(self):
        for name in ('chat-service', 'returns-service', 'billing-service'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Missing or extra'):
                validator.check_documents([item for item in self.items if not (item['kind'] == 'Deployment' and item['metadata']['name'] == name)], self.inventory)

    def test_missing_config_and_cross_service_secret_access_are_rejected(self):
        config = self.resource('ConfigMap', 'minitech-store-config')['data']
        del config['BILLING_DB_URL']
        with self.assertRaisesRegex(ValueError, 'Missing ConfigMap'): validator.check_documents(self.items, self.inventory)
        self.items = fixture()
        env = self.resource('Deployment', 'chat-service')['spec']['template']['spec']['containers'][0]['env']
        env.append({'name': 'UNEXPECTED_TOKEN_REGISTRY', 'valueFrom': {'secretKeyRef': {'name': 'minitech-store-secrets', 'key': 'PLATFORM_SERVICE_CREDENTIALS_JSON'}}})
        with self.assertRaisesRegex(ValueError, 'wrong service'): validator.check_documents(self.items, self.inventory)

    def test_unsafe_pod_or_whole_secret_mount_is_rejected(self):
        pod = self.resource('Deployment', 'api-gateway')['spec']['template']['spec']
        pod['containers'][0]['securityContext']['privileged'] = True
        with self.assertRaisesRegex(ValueError, 'container restrictions'): validator.check_documents(self.items, self.inventory)
        self.items = fixture()
        pod = self.resource('Deployment', 'product-service')['spec']['template']['spec']
        pod['containers'][0]['envFrom'].append({'secretRef': {'name': 'minitech-store-secrets'}})
        with self.assertRaisesRegex(ValueError, 'shared secret'): validator.check_documents(self.items, self.inventory)

    def test_network_rules_cannot_silently_add_or_drop_application_edges(self):
        policy = self.resource('NetworkPolicy', 'product-service')['spec']
        policy['egress'][0]['to'].append({'podSelector': {'matchLabels': {'app.kubernetes.io/name': 'payment-service'}}})
        with self.assertRaisesRegex(ValueError, 'Egress dependency'): validator.check_documents(self.items, self.inventory)
        self.items = fixture()
        policy = self.resource('NetworkPolicy', 'product-service')['spec']
        policy['egress'][0]['to'].pop()
        with self.assertRaisesRegex(ValueError, 'Egress dependency'): validator.check_documents(self.items, self.inventory)

    def test_broad_namespace_and_public_ip_exceptions_are_rejected(self):
        policy = self.resource('NetworkPolicy', 'product-service')['spec']
        policy['egress'].append({'to': [{'namespaceSelector': {}}], 'ports': [{'protocol': 'TCP', 'port': 8080}]})
        with self.assertRaisesRegex(ValueError, 'Cross-namespace'): validator.check_documents(self.items, self.inventory)
        self.items = fixture()
        policy = self.resource('NetworkPolicy', 'payment-service')['spec']
        next(peer for rule in policy['egress'] for peer in rule['to'] if 'ipBlock' in peer)['ipBlock']['except'] = ['127.0.0.0/8']
        with self.assertRaisesRegex(ValueError, 'private network'): validator.check_documents(self.items, self.inventory)

    def test_application_default_deny_does_not_select_database_or_broker_pods(self):
        selector = self.resource('NetworkPolicy', 'application-default-deny')['spec']['podSelector']['matchLabels']
        for component in ('database', 'broker'):
            infrastructure = {'app.kubernetes.io/part-of': 'mini-order-platform', 'app.kubernetes.io/component': component}
            self.assertFalse(all(infrastructure.get(key) == value for key, value in selector.items()))
        self.resource('Deployment', 'billing-service')['spec']['template']['metadata']['labels'].pop('app.kubernetes.io/component')
        with self.assertRaisesRegex(ValueError, 'isolation label'): validator.check_documents(self.items, self.inventory)

    def test_missing_ingress_namespace_and_duplicate_yaml_keys_are_rejected(self):
        self.resource('Ingress', 'minitech-store')['metadata'].pop('namespace')
        with self.assertRaisesRegex(ValueError, 'namespace'): validator.check_documents(self.items, self.inventory)
        with self.assertRaisesRegex(ValueError, 'Duplicate YAML key'): validator.documents('limits: 1\nlimits: 2\n')

    def test_inline_secrets_and_configmap_secret_fields_cannot_enter_artifacts(self):
        self.items.append({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'must-not-render', 'namespace': 'minitech-store'}, 'stringData': {'token': 'fixture'}})
        with self.assertRaisesRegex(ValueError, 'inline Secret'): validator.check_documents(self.items, self.inventory)
        self.items = fixture()
        self.resource('ConfigMap', 'minitech-store-config')['data']['PLATFORM_OPERATOR_TOKEN'] = 'fixture'
        with self.assertRaisesRegex(ValueError, 'Secret fields'): validator.check_documents(self.items, self.inventory)

    def test_literal_or_duplicate_environment_cannot_bypass_secret_bindings(self):
        env = self.resource('Deployment', 'notification-service')['spec']['template']['spec']['containers'][0]['env']
        env.append({'name': 'LEAKED_SECRET', 'value': 'fixture'})
        with self.assertRaisesRegex(ValueError, 'Literal environment'): validator.check_documents(self.items, self.inventory)
        self.items = fixture()
        env = self.resource('Deployment', 'notification-service')['spec']['template']['spec']['containers'][0]['env']
        env.append(copy.deepcopy(env[0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate environment'): validator.check_documents(self.items, self.inventory)

    def test_release_rejects_deferred_payment_and_target_placeholders(self):
        with self.assertRaisesRegex(ValueError, 'A05'): validator.check_documents(self.items, self.inventory, release=True)
        self.resource('ConfigMap', 'minitech-store-config')['data']['PAYMENT_PROVIDER'] = 'stripe'
        with self.assertRaisesRegex(ValueError, 'placeholder'): validator.check_documents(self.items, self.inventory, release=True)

    def test_configured_release_uses_real_hosts_proxy_trust_and_commit_images(self):
        config = self.resource('ConfigMap', 'minitech-store-config')['data']
        for key, value in config.items():
            config[key] = value.replace('minitech.example', 'shop.audit.invalid').replace('smtp.replace-with-provider.example', 'smtp.audit.invalid')
        config['PAYMENT_PROVIDER'] = 'stripe'
        config['GATEWAY_TRUSTED_PROXY_CIDRS'] = '10.244.0.0/16'
        for item in self.items:
            if item['kind'] == 'Deployment': item['spec']['template']['spec']['containers'][0]['image'] = 'ghcr.io/audit/app/' + item['metadata']['name'] + ':' + 'a' * 40
        ingress = self.resource('Ingress', 'minitech-store')['spec']
        ingress['rules'][0]['host'] = 'shop.audit.invalid'; ingress['tls'][0]['hosts'] = ['shop.audit.invalid']
        validator.check_documents(self.items, self.inventory, release=True)
        config['GATEWAY_TRUSTED_PROXY_CIDRS'] = '0.0.0.0/0'
        with self.assertRaisesRegex(ValueError, 'every address'): validator.check_documents(self.items, self.inventory, release=True)

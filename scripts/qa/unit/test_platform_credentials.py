import importlib.util
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    module_directory = str((ROOT / path).parent)
    sys.path.insert(0, module_directory)
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.remove(module_directory)
    return module


credentials = load('platform_credentials', 'scripts/security/credentials.py')
server = load('control_center_security', 'scripts/control-center/web/server.py')


class CredentialTests(unittest.TestCase):
    def test_mfa_provisioning_preserves_the_exact_previous_derivation(self):
        for values, material in [({'CUSTOMER_JWT_SECRET': 'old-hmac'}, 'old-hmac'),
                ({'CUSTOMER_JWT_SECRET': 'old-hmac', 'CUSTOMER_JWT_PRIVATE_KEY_BASE64': 'old-rsa'}, 'old-rsa'),
                ({'CUSTOMER_MFA_ENCRYPTION_KEY_BASE64': 'old-direct', 'CUSTOMER_JWT_PRIVATE_KEY_BASE64': 'old-rsa'}, 'old-direct')]:
            provisioned = credentials.ensure_mfa(values)
            self.assertEqual(base64.b64encode(hashlib.sha256(material.encode()).digest()).decode(), provisioned['CUSTOMER_MFA_LEGACY_KEY_BASE64'])
            self.assertEqual(32, len(base64.b64decode(provisioned['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'])))
            combined = dict(values, **provisioned)
            combined['CUSTOMER_JWT_PRIVATE_KEY_BASE64'] = 'rotated-rsa'
            self.assertEqual({}, credentials.ensure_mfa(combined))

    def test_mfa_rotation_keeps_previous_keys_and_legacy_key(self):
        values = credentials.ensure_mfa({'CUSTOMER_JWT_SECRET': 'old-hmac'})
        original_key, original_id = values['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'], values['CUSTOMER_MFA_ACTIVE_KID']
        rotated = dict(values, **credentials.ensure_mfa(values, rotate=True))
        self.assertNotEqual(original_key, rotated['CUSTOMER_MFA_ENCRYPTION_KEY_BASE64'])
        self.assertNotEqual(original_id, rotated['CUSTOMER_MFA_ACTIVE_KID'])
        self.assertEqual(original_key, json.loads(rotated['CUSTOMER_MFA_PREVIOUS_KEYS_JSON'])[original_id])
        self.assertEqual(values['CUSTOMER_MFA_LEGACY_KEY_BASE64'], rotated['CUSTOMER_MFA_LEGACY_KEY_BASE64'])
        self.assertEqual({}, credentials.ensure_mfa(rotated))

    def test_mfa_retirement_does_not_recreate_a_key_from_jwt(self):
        values = credentials.ensure_mfa({'CUSTOMER_JWT_SECRET': 'old-hmac'})
        del values['CUSTOMER_MFA_LEGACY_KEY_BASE64']
        values['CUSTOMER_JWT_SECRET'] = 'different-jwt'
        self.assertEqual({}, credentials.ensure_mfa(values))

    def test_invalid_configured_mfa_key_is_never_replaced_silently(self):
        with self.assertRaises(ValueError):
            credentials.ensure_mfa({'CUSTOMER_MFA_KEY_FORMAT': 'v1', 'CUSTOMER_MFA_ENCRYPTION_KEY_BASE64': 'broken'})
        with self.assertRaises(ValueError):
            credentials.ensure_mfa({'CUSTOMER_MFA_KEY_FORMAT': 'v1'})

    def test_provisioning_is_idempotent_and_preserves_existing_secrets(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            path = Path(directory) / 'private.env'
            path.write_text('DB_PASSWORD="unchanged"\n', encoding='utf-8')
            self.assertTrue(credentials.ensure(path))
            before = path.read_text(encoding='utf-8')
            self.assertFalse(credentials.ensure(path))
            self.assertEqual(before, path.read_text(encoding='utf-8'))
            values = credentials.read_dotenv(before)
            self.assertEqual('unchanged', values['DB_PASSWORD'])
            registry = json.loads(values['PLATFORM_SERVICE_CREDENTIALS_JSON'])
            self.assertEqual(14, len(registry))
            self.assertEqual(14, len(set(registry.values())))
            self.assertNotIn(values['PLATFORM_OPERATOR_TOKEN'], registry.values())
            for service, token in registry.items():
                self.assertEqual(token, values['PLATFORM_' + service.upper().replace('-', '_') + '_TOKEN'])

    def test_operator_credential_is_sent_only_to_known_platform_origins(self):
        with patch.dict(os.environ, {'PLATFORM_OPERATOR_TOKEN': 'private-operator-token'}):
            local = server.platform_request('http://127.0.0.1:18080/api/orders')
            self.assertEqual('Bearer private-operator-token', local.get_header('Authorization'))
            for url in ['https://example.com/api/orders', 'http://127.0.0.1:18025/api/v1/messages',
                        'http://127.0.0.1.evil.example:18080/api/orders', 'http://localhost:9999/api/orders']:
                self.assertIsNone(server.platform_request(url).get_header('Authorization'))

    def test_denied_restock_never_writes_directly_to_database(self):
        with patch.object(server, 'api_json', side_effect=RuntimeError('403')), patch.object(server, 'run_docker_cli') as docker:
            with self.assertRaises(RuntimeError): server.restock_inventory(1, 5, '00000000-0000-4000-8000-000000000001')
            docker.assert_not_called()

    def test_private_sql_fallback_requires_verified_operator(self):
        with patch.object(server, 'api_json', return_value={'role': 'CUSTOMER'}), patch.object(server, 'run_docker_cli') as docker:
            with self.assertRaises(PermissionError): server.psql_query_json('order', 'SELECT id FROM orders')
            docker.assert_not_called()

    def test_operator_panel_reports_denied_data_access_without_sql_fallback(self):
        handler = object.__new__(server.Handler)
        handler.path = '/api/data/orders'
        handler.send_json = Mock()
        with patch.object(server, 'api_json', return_value={'role': 'CUSTOMER'}), patch.object(server, 'get_orders_data') as orders:
            handler.do_GET()
            orders.assert_not_called()
            self.assertEqual(403, handler.send_json.call_args.args[1])

    def test_invoice_generation_requires_live_order_verification(self):
        with patch.object(server, 'api_json', side_effect=RuntimeError('offline')), patch.object(server, 'psql_query_json') as sql:
            result = server.generate_invoice_for_order(1)
            self.assertFalse(result['success'])
            sql.assert_not_called()

    def test_invoice_generation_rejects_unpaid_and_empty_orders(self):
        for order in [{'id': 1, 'status': 'CONFIRMED'}, {'id': 1, 'status': 'PAID', 'items': []}]:
            with patch.object(server, 'api_json', return_value=order), patch.object(server.urllib.request, 'urlopen') as post:
                self.assertFalse(server.generate_invoice_for_order(1)['success'])
                post.assert_not_called()

    def test_invoice_generation_reports_billing_rejection(self):
        order = {'id': 1, 'status': 'PAID', 'customerEmail': 'buyer@example.test', 'items': [
                 {'productId': 7, 'sku': None, 'productName': 'Produkt', 'quantity': 1, 'unitPrice': '12.00', 'totalPrice': '12.00'}],
                 'totalPrice': '12.00', 'deliveryCost': '0.00', 'discountAmount': '0.00'}
        error = server.urllib.error.HTTPError('http://localhost/invoices', 400, 'Bad request', {}, None)
        with patch.object(server, 'api_json', return_value=order), patch.object(server, 'platform_request') as request, \
             patch.object(server.urllib.request, 'urlopen', side_effect=error):
            result = server.generate_invoice_for_order(1)
            self.assertFalse(result['success'])
            self.assertIn('400', result['error'])
            payload = json.loads(request.call_args.kwargs['data'])
            self.assertIsNone(payload['items'][0]['sku'])
            self.assertEqual('12.00', payload['grossAmount'])


if __name__ == '__main__':
    unittest.main()

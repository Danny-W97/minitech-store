import ast
import importlib.util
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[3]
WEB = ROOT / 'scripts/control-center/web'
sys.path.insert(0, str(WEB))
import cc_ai
import cc_data
import cc_transport

spec = importlib.util.spec_from_file_location('module_layout', ROOT / 'scripts/qa/module-layout-check.py')
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)


class ModuleLayoutTests(unittest.TestCase):
    def copy(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        directory = tempfile.TemporaryDirectory(prefix='a42-module-test-', dir=ROOT / 'tmp')
        self.addCleanup(directory.cleanup)
        folder = Path(directory.name) / 'scripts/control-center/web'
        folder.mkdir(parents=True)
        for name in ['server', *layout.FEATURES]:
            shutil.copyfile(WEB / (name + '.py'), folder / (name + '.py'))
        return Path(directory.name), folder

    def test_real_layout_has_one_http_implementation_and_thin_facades(self):
        result = layout.check()
        self.assertEqual(56, result['facades'])
        self.assertEqual(15, result['featureModules'])

    def test_a_copied_body_is_rejected(self):
        root, folder = self.copy()
        path = folder / 'server.py'
        tree = ast.parse(path.read_text(encoding='utf-8'))
        next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'api_json').body.insert(0, ast.Pass())
        path.write_text(ast.unparse(ast.fix_missing_locations(tree)), encoding='utf-8')
        with self.assertRaises(ValueError): layout.check(root)

    def test_a_reverse_import_is_rejected(self):
        root, folder = self.copy()
        with (folder / 'cc_ai.py').open('a', encoding='utf-8') as output: output.write('\nimport server\n')
        with self.assertRaises(ValueError): layout.check(root)

    def test_replacing_the_http_adapter_is_rejected(self):
        root, folder = self.copy()
        path = folder / 'server.py'
        path.write_text(path.read_text(encoding='utf-8').replace('class Handler(ControlHttpHandler):', 'class Handler(SimpleHTTPRequestHandler):'), encoding='utf-8')
        with self.assertRaises(ValueError): layout.check(root)

    def test_data_module_accepts_a_small_fake_without_importing_or_starting_server(self):
        context = SimpleNamespace(GATEWAY_API='https://fixture.test/api',
            api_page=lambda url, params: {'items': [{'id': 7}], 'hasMore': False},
            psql_query_json=lambda *args: [{'order_id': 7, 'invoice_number': 'FV-FIXTURE'}])
        result = cc_data.get_orders_data(context, {})
        self.assertEqual('FV-FIXTURE', result['orders'][0]['invoiceNumber'])

    def test_transport_validation_is_independent_of_operator_credentials(self):
        result = cc_transport.export_params(SimpleNamespace(), {'from': ['2026-10-27T00:00:00Z']})
        self.assertTrue(result['from'].endswith('+00:00'))
        with self.assertRaises(ValueError): cc_transport.export_params(SimpleNamespace(), {'from': ['2026-10-27']})

    def test_builtin_ai_needs_no_key_network_or_real_runtime(self):
        context = SimpleNamespace(load_ai_config=lambda: {'provider': 'builtin'},
            get_project_system_prompt=lambda lang: 'fixture context',
            generate_local_knowledge_reply=lambda message, lang: 'fixture reply')
        self.assertEqual('fixture reply', cc_ai.handle_ai_chat(context, 'test', [], 'pl')['reply'])

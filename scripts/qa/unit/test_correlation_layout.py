import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('correlation_layout',ROOT/'scripts/qa/correlation-layout-check.py')
layout=importlib.util.module_from_spec(spec);spec.loader.exec_module(layout)


class CorrelationLayoutTests(unittest.TestCase):
    def setUp(self):
        directory=tempfile.TemporaryDirectory(prefix='correlation-layout-',dir=ROOT)
        self.addCleanup(directory.cleanup);self.root=Path(directory.name)
        for name in ['CorrelationIdContext','CorrelationIdClientInterceptor']:
            target=self.root/'platform-security/src/main/java/org/demo/platformobservability'/(name+'.java')
            target.parent.mkdir(parents=True,exist_ok=True);target.write_text('shared implementation')
        self.adapter=self.root/'example-service/src/main/java/org/demo/example/observability/CorrelationIdFilter.java'
        self.adapter.parent.mkdir(parents=True,exist_ok=True)
        self.adapter.write_text('@Component class CorrelationIdFilter extends org.demo.platformobservability.CorrelationIdFilter {}')
        target=self.root/'deploy/components.json';target.parent.mkdir(parents=True)
        target.write_text(json.dumps({'components':[{'name':'example-service','runtime':'jvm'}]}))

    def test_repository_and_minimal_layout_are_valid(self):
        self.assertEqual(15,layout.check(ROOT)['serviceAdapters'])
        self.assertEqual(1,layout.check(self.root)['serviceAdapters'])

    def test_a_local_context_copy_is_rejected(self):
        self.adapter.with_name('CorrelationIdContext.java').write_text('class CorrelationIdContext {}')
        with self.assertRaisesRegex(ValueError,'Only one shared'):layout.check(self.root)

    def test_a_local_filter_cannot_reimplement_the_header_contract(self):
        self.adapter.write_text('@Component class CorrelationIdFilter extends OncePerRequestFilter {}')
        with self.assertRaisesRegex(ValueError,'must delegate'):layout.check(self.root)

    def test_an_old_import_cannot_survive_as_a_stale_class_reference(self):
        self.adapter.with_name('Client.java').write_text('import org.demo.example.observability.CorrelationIdContext;')
        with self.assertRaisesRegex(ValueError,'Legacy correlation reference'):layout.check(self.root)
    def test_missing_component_adapter_is_rejected(self):
        p=self.root/'deploy/components.json';d=json.loads(p.read_text());d['components'].append({'name':'missing-service','runtime':'jvm'});p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ValueError,'Every JVM component'):layout.check(self.root)
    def test_two_adapters_in_one_service_are_rejected(self):
        other=self.adapter.parent/'nested/CorrelationIdFilter.java';other.parent.mkdir();other.write_text(self.adapter.read_text())
        with self.assertRaisesRegex(ValueError,'Every JVM component'):layout.check(self.root)

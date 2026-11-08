import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('maven_layout', ROOT / 'scripts/qa/maven-layout-check.py')
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)


class MavenLayoutTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='maven-layout-test-', dir=ROOT)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for path in [ROOT / 'pom.xml', ROOT / 'deploy/components.json', *ROOT.glob('*/pom.xml')]:
            target = self.root / path.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)

    def replace(self, path, old, new):
        target = self.root / path
        text = target.read_text(encoding='utf-8')
        self.assertIn(old, text)
        target.write_text(text.replace(old, new, 1), encoding='utf-8')

    def test_current_reactor_and_module_specific_pdf_dependency_are_valid(self):
        result = layout.check(self.root)
        self.assertEqual(17, result['modules'])
        self.assertIn('<pdfbox.version>', (self.root / 'billing-service/pom.xml').read_text())

    def test_bypassing_repository_parent_is_rejected(self):
        self.replace('chat-service/pom.xml', '<artifactId>mini-order-platform</artifactId>', '<artifactId>spring-boot-starter-parent</artifactId>')
        with self.assertRaisesRegex(ValueError, 'chat-service: must inherit'):
            layout.check(self.root)

    def test_even_an_identical_local_version_would_break_single_source(self):
        self.replace('chat-service/pom.xml', '<dependencies>', '<properties><java.version>25</java.version></properties><dependencies>')
        with self.assertRaisesRegex(ValueError, 'duplicated shared properties'):
            layout.check(self.root)

    def test_module_cannot_override_managed_jdbc_version(self):
        self.replace('chat-service/pom.xml', '<artifactId>postgresql</artifactId>', '<artifactId>postgresql</artifactId><version>0.0</version>')
        with self.assertRaisesRegex(ValueError, 'managed dependency version'):
            layout.check(self.root)

    def test_parent_cannot_force_application_dependencies_into_libraries(self):
        self.replace('pom.xml', '<modules>', '<dependencies><dependency><groupId>example</groupId><artifactId>application</artifactId><version>1</version></dependency></dependencies><modules>')
        with self.assertRaisesRegex(ValueError, 'runtime dependencies'):
            layout.check(self.root)

    def test_parent_cannot_activate_repackage_for_the_shared_library(self):
        self.replace('pom.xml', '<pluginManagement>', '<plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins><pluginManagement>')
        with self.assertRaisesRegex(ValueError, 'activate plugins'):
            layout.check(self.root)

    def test_missing_deployable_module_is_detected(self):
        self.replace('pom.xml', '<module>chat-service</module>', '')
        with self.assertRaisesRegex(ValueError, 'component inventory'):
            layout.check(self.root)

    def test_integration_profile_cannot_pin_its_own_failsafe_version(self):
        self.replace('customer-service/pom.xml', '<artifactId>maven-failsafe-plugin</artifactId>', '<artifactId>maven-failsafe-plugin</artifactId><version>0.0</version>')
        with self.assertRaisesRegex(ValueError, 'managed plugin version'):
            layout.check(self.root)

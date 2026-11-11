import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('resilience_layout',ROOT/'scripts/qa/resilience-layout-check.py')
layout=importlib.util.module_from_spec(spec);spec.loader.exec_module(layout)


class ResilienceLayoutTests(unittest.TestCase):
    def setUp(self):
        directory=tempfile.TemporaryDirectory(prefix='resilience-layout-',dir=ROOT)
        self.addCleanup(directory.cleanup);self.root=Path(directory.name)
        paths=[ROOT/'platform-security/pom.xml']
        for name in ('ResilientHttpExecutor.java','ResilienceConfiguration.java','ResilienceConfig.java'):
            paths.extend(ROOT.glob('*/src/main/java/**/'+name))
        for path in paths:
            target=self.root/path.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)

    def replace(self,path,old,new):
        text=path.read_text();self.assertIn(old,text);path.write_text(text.replace(old,new,1))

    def test_current_layout_keeps_only_four_runtime_consumers(self):
        self.assertEqual(4,layout.check(self.root)['serviceAdapters'])

    def test_optional_library_dependency_cannot_leak_into_every_service(self):
        self.replace(self.root/'platform-security/pom.xml','<optional>true</optional>','<optional>false</optional>')
        with self.assertRaisesRegex(ValueError,'must be optional'):layout.check(self.root)

    def test_adapter_cannot_reintroduce_local_configuration(self):
        path=next((self.root/'api-gateway').rglob('ResilienceConfig.java'))
        self.replace(path,'{}','{ @Bean public Object duplicate() { return null; } }')
        with self.assertRaisesRegex(ValueError,'duplicates resilience policy'):layout.check(self.root)

    def test_shared_configuration_cannot_activate_globally(self):
        path=next((self.root/'platform-security').rglob('ResilienceConfiguration.java'))
        self.replace(path,'public class ResilienceConfiguration','@Configuration public class ResilienceConfiguration')
        with self.assertRaisesRegex(ValueError,'must not activate itself'):layout.check(self.root)

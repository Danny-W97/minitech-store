"""Configuration checks must not certify behavior they do not execute."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('operational_retention', ROOT / 'scripts/qa/operational-retention-check.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OperationalRetentionCheckTest(unittest.TestCase):
    def test_config_pass_never_claims_behavior_or_storage_acceptance(self):
        result = module.check(ROOT)
        self.assertEqual('PASS', result['status'])
        self.assertEqual('configuration-and-source-presence-only', result['scope'])
        self.assertIsNone(result['p09FailClosedEnforced'])
        self.assertFalse(result['storageIntegrationVerified'])
        self.assertFalse(result['cleanupExecutionImplemented'])
        self.assertFalse(result['manualProcedureParameters']['workerStopAcknowledgementImplemented'])
        self.assertFalse(result['mailpitParameters']['attachmentDeletionVerified'])
        self.assertFalse(result['activationAllowed'])

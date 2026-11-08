import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("a43_readiness", ROOT / "scripts/qa/a43-final-readiness-check.py")
a43_readiness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a43_readiness)


class A43FinalReadinessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = a43_readiness.verify_readiness(ROOT)

    def test_verify_readiness_structure_and_fail_closed_invariants(self):
        report = self.report
        self.assertEqual("A43.41", report["stage"])
        self.assertEqual("A43-Step-6-Readiness", report["step"])
        self.assertEqual("PASS", report["status"])
        self.assertEqual("NOT_READY", report["readinessStatus"])
        self.assertEqual(2, report["readinessExitCode"])
        self.assertTrue(report["failClosed"])
        self.assertFalse(report["activationAllowed"])
        self.assertFalse(report["runtimeWired"])
        self.assertEqual(0, report["executedDataMutations"])
        self.assertEqual(0, report["auditorExecutedDataMutations"])
        self.assertEqual("BLOCKED_FAIL_CLOSED", report["runtimeD02RedactionWorker"])
        self.assertEqual("ENABLED_IN_CUSTOMER_SERVICE", report["customerSecurityRetentionWorker"])
        self.assertFalse(report["secretValuesRead"])
        self.assertTrue(report["decisionFormRequired"])
        self.assertIn("baseline_receipt_partial_fail_closed", report["decisionReceiptBaseline"])
        self.assertEqual("5_years_financial_retention_hard_block_dev_test", report["userResolvedPolicy"]["D01"])

    def test_service_architecture_coverage(self):
        services = self.report["dimensions"]["serviceArchitecture"]
        self.assertEqual("VERIFIED", services["status"])
        self.assertEqual(15, services["totalServices"])
        self.assertIn("api-gateway", services["jvmServices"])
        self.assertIn("returns-service", services["jvmServices"])
        self.assertTrue(services["gatewayRoutingConfigured"])
        self.assertEqual("W3C-traceparent", services["traceContextPropagation"])

    def test_schema_and_retention_classification_counts(self):
        schema = self.report["dimensions"]["schemaAndClassification"]
        self.assertEqual("VERIFIED", schema["status"])
        self.assertEqual(145, schema["tables"])
        self.assertEqual(1307, schema["columns"])
        self.assertEqual(232, schema["migrations"])
        self.assertEqual(10, schema["d02CommercialTables"])
        self.assertEqual(5, schema["financialTablesProtected"])
        self.assertTrue(schema["retentionWorkflowsFailClosed"])

    def test_critical_browser_matrix_invariants(self):
        browser = self.report["dimensions"]["criticalBrowserMatrix"]
        self.assertEqual("VERIFIED", browser["status"])
        self.assertEqual(28, browser["requiredCombinations"])
        self.assertEqual(7, browser["recoveryScenarios"])
        self.assertEqual(4, len(browser["engines"]))
        self.assertEqual(0, browser["maxRetries"])
        self.assertFalse(browser["allowFlaky"])

    def test_observability_and_slo_alerts(self):
        obs = self.report["dimensions"]["observabilityAndSlo"]
        self.assertEqual("VERIFIED", obs["status"])
        self.assertEqual("vector(1)", obs["watchdogHeartbeat"])
        self.assertIn("BusinessQueueSloBreach", obs["sloAlertsEnforced"])
        self.assertIn("RedisStreamLagHigh", obs["sloAlertsEnforced"])
        self.assertEqual(6, obs["monitoredDatabases"])
        self.assertEqual("static_contract_and_fixture_self_test_live_requires_live_postgres_flag", obs["consistencyAuditScope"])

    def test_recovery_and_replay_invariants(self):
        recovery = self.report["dimensions"]["recoveryAndReplay"]
        self.assertEqual("VERIFIED", recovery["status"])
        self.assertEqual("Age (X25519 + ChaCha20-Poly1305)", recovery["encryptionFormat"])
        self.assertEqual("manifest.json.age", recovery["authenticatedManifest"])
        self.assertEqual("ENFORCED", recovery["replayQuarantine"])
        self.assertTrue(recovery["restorationInvalidatesStaleAssessments"])

    def test_blockers_and_decision_accounting(self):
        blockers = self.report["blockers"]
        self.assertTrue(len(blockers) >= 5)
        blocker_text = " ".join(blockers)
        self.assertIn("D01", blocker_text)
        self.assertIn("D09", blocker_text)
        self.assertIn("P09", blocker_text)
        self.assertIn("D17", blocker_text)


if __name__ == "__main__":
    unittest.main()

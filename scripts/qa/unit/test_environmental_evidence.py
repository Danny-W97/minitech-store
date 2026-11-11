import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("env_evidence", ROOT / "scripts/qa/environmental-evidence-check.py")
env_evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(env_evidence)


class EnvironmentalEvidenceTests(unittest.TestCase):
    def test_collect_evidence_returns_complete_verified_structure(self):
        evidence = env_evidence.collect_evidence(ROOT)
        self.assertEqual("A43.40-Step-5", evidence["step"])
        self.assertFalse(evidence["secretValuesRead"])
        self.assertTrue(evidence["readOnly"])
        self.assertEqual(0, evidence["executedMutations"])
        self.assertEqual(["dev", "test"], evidence["environments"])

        categories = evidence["evidenceCategories"]
        self.assertEqual(8, len(categories))
        for key in (
            "clocksAndTimezone",
            "redisStreamsAndGroups",
            "backupSetsAndRetention",
            "smtpAndEmailDelivery",
            "aiAssistantAndEgress",
            "keysMfaAndCredentials",
            "operatorRolesAndGovernance",
            "privacyJournalCustody",
        ):
            self.assertIn(key, categories)
            self.assertEqual("VERIFIED", categories[key]["status"])

    def test_clocks_and_timezone_invariants(self):
        evidence = env_evidence.collect_evidence(ROOT)
        tz_info = evidence["evidenceCategories"]["clocksAndTimezone"]
        self.assertEqual("UTC", tz_info["standardTimezone"])
        self.assertEqual("-Duser.timezone=UTC", tz_info["jvmTimezoneFlag"])
        self.assertEqual("ENFORCED", tz_info["iso8601UtcSerialization"])

    def test_smtp_mailpit_boundaries_in_dev_and_test(self):
        evidence = env_evidence.collect_evidence(ROOT)
        smtp_info = evidence["evidenceCategories"]["smtpAndEmailDelivery"]
        self.assertEqual(11025, smtp_info["devDelivery"]["port"])
        self.assertEqual(12025, smtp_info["testDelivery"]["port"])
        self.assertFalse(smtp_info["devDelivery"]["smtpAuth"])
        self.assertFalse(smtp_info["testDelivery"]["smtpAuth"])
        self.assertEqual(1000, smtp_info["mailpitMaxMessages"])
        self.assertEqual(30, smtp_info["mailpitMaxAgeDays"])

    def test_operator_roles_and_governance(self):
        evidence = env_evidence.collect_evidence(ROOT)
        roles = evidence["evidenceCategories"]["operatorRolesAndGovernance"]
        self.assertEqual("operator-1", roles["owner"])
        self.assertEqual("operator-2", roles["deputy"])
        self.assertEqual("owner-only-operator-1-no-second-person-requirement", roles["d15AmbiguousReview"])

    def test_privacy_journal_paths_and_safeguards(self):
        evidence = env_evidence.collect_evidence(ROOT)
        journal = evidence["evidenceCategories"]["privacyJournalCustody"]
        self.assertIn("dev", journal["journalDirectories"])
        self.assertIn("test", journal["journalDirectories"])
        self.assertEqual("SAFE", journal["headCustodyFolder"])
        self.assertEqual("privacy-journal-safe", journal["safeVolumeName"])
        self.assertEqual("Ed25519", journal["signatureAlgorithm"])


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Operational retention and manual procedures verification (D07, D08, D14, D15, D18, P09).

Checks declared configuration and source presence only. Behavior and real storage
integration require separate executable tests; this script never certifies deletion safety.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]


def check(root: Path = ROOT) -> dict:
    root = Path(root).resolve()

    # 1. Review docker-compose.monitoring.yaml
    monitoring_compose_file = root / "docker-compose.monitoring.yaml"
    monitoring_compose = yaml.safe_load(monitoring_compose_file.read_text(encoding="utf-8"))

    prom_cmd = monitoring_compose["services"]["prometheus"]["command"]
    alert_cmd = monitoring_compose["services"]["alertmanager"]["command"]

    prom_has_retention = any("--storage.tsdb.retention.time=30d" in arg for arg in prom_cmd)
    alert_has_retention = any("--data.retention=120h" in arg for arg in alert_cmd)

    if not prom_has_retention:
        raise ValueError("Prometheus is missing required 30-day TSDB retention command line flag")
    if not alert_has_retention:
        raise ValueError("Alertmanager is missing required 120h (5-day) native retention command line flag")

    # 2. Review compose.yaml
    compose_file = root / "compose.yaml"
    compose = yaml.safe_load(compose_file.read_text(encoding="utf-8"))

    mailpit_env = compose["services"]["mailpit"]["environment"]
    max_messages = mailpit_env.get("MP_MAX_MESSAGES")
    if max_messages != 1000 and max_messages != "1000":
        raise ValueError(f"Mailpit MP_MAX_MESSAGES must be 1000, got: {max_messages}")

    # 3. Review retention-decisions.json
    decisions_path = root / "scripts/qa/contracts/retention-decisions.json"
    decisions = json.loads(decisions_path.read_text(encoding="utf-8"))["decisions"]

    d07 = decisions["D07"]
    if d07["maximumAgeDays"] != 30 or d07["maximumBytesPerSourceIncludingRotations"] != 104857600:
        raise ValueError("D07 contract parameters do not match approved 30d/100MiB limits")

    d08 = decisions["D08"]
    if d08["prometheus"]["ageDays"] != 30 or d08["alertmanager"]["nativeRetentionHours"] != 120:
        raise ValueError("D08 contract parameters do not match approved 30d/120h limits")

    d14 = decisions["D14"]
    if d14["mailpit"]["maximumAgeDays"] != 30 or d14["mailpit"]["maximumMessagesPerInstance"] != 1000:
        raise ValueError("D14 contract parameters do not match approved 30d/1000msg limits")

    d15 = decisions["D15"]
    if d15["evidenceAgeDays"] != 90 or d15["minimumCaseRecordAgeDays"] != 365:
        raise ValueError("D15 contract parameters do not match approved 90d/365d limits")
    if d15["additionalReviewer"] != "owner-only-operator-1-no-second-person-requirement":
        raise ValueError("D15 ambiguous review must be restricted to owner-only-operator-1")

    d18 = decisions["D18"]
    if d18["owner"] != "operator-1" or d18["deputy"] != "operator-2":
        raise ValueError("D18 roles must match operator-1 (owner) and operator-2 (deputy)")

    # 4. Java verification classes existence and hash
    java_root = root / "platform-security/src/main/java/org/demo/platformsecurity/privacy"
    required_classes = [
        "OperationalRetentionGuard.java",
        "OperationalLogRetention.java",
        "MonitoringRetentionPolicy.java",
        "MailpitRetentionManager.java",
        "ManualCaseProcedureService.java",
    ]
    class_evidence = []
    for cls_name in required_classes:
        p = java_root / cls_name
        if not p.is_file():
            raise ValueError(f"Missing required operational retention class: {cls_name}")
        raw = p.read_bytes()
        class_evidence.append({
            "class": cls_name,
            "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw),
        })

    return {
        "stage": "A43.40-P122",
        "status": "PASS",
        "scope": "configuration-and-source-presence-only",
        "behavioralEvidenceRequired": True,
        "p09FailClosedEnforced": None,
        "storageIntegrationVerified": False,
        "cleanupExecutionImplemented": False,
        "monitoringParameters": {
            "prometheusTsdbRetention": "30d",
            "alertmanagerRetention": "120h",
            "storageBudgetAlarmBytes": 1073741824,
            "sizeMayRemoveEarlier": False,
        },
        "mailpitParameters": {
            "maxMessagesPerInstance": 1000,
            "maxAgeDays": 30,
            "attachmentDeletionVerified": False,
        },
        "logParameters": {
            "maxAgeDays": 30,
            "maxBytesPerSource": 104857600,
            "legacyD10Protected": True,
            "certifiedJobLogsProtected": True,
        },
        "manualProcedureParameters": {
            "owner": "operator-1",
            "deputy": "operator-2",
            "ambiguousCaseReviewExclusiveToOwner": True,
            "evidenceRetentionDays": 90,
            "minimalRecordRetentionDays": 365,
            "workerStopAcknowledgementImplemented": False,
        },
        "classEvidence": class_evidence,
        "runtimeWired": False,
        "activationAllowed": False,
    }


if __name__ == "__main__":
    print(json.dumps(check(), indent=2))

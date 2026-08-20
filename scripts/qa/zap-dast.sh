#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${ZAP_API_BASE_URL:-http://127.0.0.1:18080}"
BASE_URL="${BASE_URL%/}"
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}"
REPORT_DIRECTORY="${ZAP_REPORT_DIRECTORY:-$PROJECT_ROOT/gitIgnoreOwn/runtime/zap}"
SPIDER_MINUTES="${ZAP_SPIDER_MINUTES:-2}"
STARTUP_TIMEOUT_MINUTES="${ZAP_STARTUP_TIMEOUT_MINUTES:-5}"

for command in curl docker python3; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Brak wymaganej komendy ZAP DAST: %s\n' "$command" >&2
    exit 1
  }
done

mkdir -p "$REPORT_DIRECTORY"
chmod 777 "$REPORT_DIRECTORY"

curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
  "$BASE_URL/actuator/health" >/dev/null || {
  printf 'ZAP DAST: API Gateway jest niedostepny pod %s\n' "$BASE_URL" >&2
  exit 1
}

printf 'OWASP ZAP full scan: %s\n' "$BASE_URL"
printf 'Raporty: %s\n' "$REPORT_DIRECTORY"

set +e
docker run --rm --network host \
  -v "$REPORT_DIRECTORY:/zap/wrk/:rw" \
  "$ZAP_IMAGE" zap-full-scan.py \
  -t "$BASE_URL" \
  -m "$SPIDER_MINUTES" \
  -T "$STARTUP_TIMEOUT_MINUTES" \
  -I -s \
  -r /zap/wrk/zap-full-report.html \
  -J /zap/wrk/zap-full-report.json
zap_status=$?
set -e

if (( zap_status != 0 && zap_status != 1 && zap_status != 2 )); then
  printf 'ZAP DAST zakończony błędem technicznym (kod %s).\n' "$zap_status" >&2
  exit "$zap_status"
fi

report_file="$REPORT_DIRECTORY/zap-full-report.json"
[[ -s "$report_file" ]] || {
  printf 'ZAP DAST nie utworzył raportu JSON: %s\n' "$report_file" >&2
  exit 1
}

python3 - "$report_file" "$BASE_URL" <<'PY'
import json
import sys
from urllib.parse import urlsplit

report_path = sys.argv[1]
base_url = sys.argv[2]
with open(report_path, encoding="utf-8") as handle:
    document = json.load(handle)

findings = []
accepted = []

expected = urlsplit(base_url + "/actuator/health")

def instances_of(alert):
    instances = alert.get("instances", [])
    if isinstance(instances, dict):
        return [instances]
    return instances if isinstance(instances, list) else []

def is_minimal_health_alert(alert):
    if alert.get("alert", alert.get("name")) != "Spring Actuator Information Leak":
        return False
    instances = instances_of(alert)
    if not instances:
        return False
    for instance in instances:
        uri = urlsplit(instance.get("uri", instance.get("url", "")))
        if (uri.scheme, uri.netloc, uri.path, uri.query) != (
            expected.scheme, expected.netloc, expected.path, ""
        ):
            return False
        if instance.get("method", "GET").upper() != "GET":
            return False
        try:
            evidence = json.loads(instance.get("evidence", ""))
        except (TypeError, json.JSONDecodeError):
            return False
        if evidence.get("status") != "UP":
            return False
        if set(evidence) - {"status", "groups"}:
            return False
        if "components" in evidence or "details" in evidence:
            return False
    return True

for site in document.get("site", []):
    for alert in site.get("alerts", []):
        if "riskcode" in alert:
            try:
                risk = int(str(alert["riskcode"]))
            except ValueError:
                risk = -1
            if risk >= 2:
                if is_minimal_health_alert(alert):
                    accepted.append(alert.get("alert", "Spring Actuator Information Leak"))
                    continue
                instances = instances_of(alert)
                findings.append({
                    "risk": alert.get("riskdesc", alert.get("riskcode")),
                    "alert": alert.get("alert", alert.get("name", "unknown")),
                    "url": instances[0].get("uri", instances[0].get("url", "")) if instances else "",
                })
unique = {(item["risk"], item["alert"], item["url"]): item for item in findings}
if unique:
    print("ZAP DAST FAILED: znaleziono alerty srednie/wysokie:")
    for item in sorted(unique.values(), key=lambda item: (str(item["risk"]), item["alert"], item["url"])):
        print(" - {risk}: {alert} {url}".format(**item))
    sys.exit(1)

if accepted:
    print("ZAP policy: zaakceptowano status-only /actuator/health bez komponentow i szczegolow.")
print("ZAP DAST OK: brak niedopuszczonych alertow srednich i wysokich.")
PY

printf 'Raport HTML: %s/zap-full-report.html\n' "$REPORT_DIRECTORY"
printf 'Raport JSON: %s\n' "$report_file"

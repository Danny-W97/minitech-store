"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
import json
import re

def load_error_database(services) -> list[dict[str, Any]]:
    if services.ERROR_DATABASE_PATH.is_file():
        try:
            data = json.loads(services.ERROR_DATABASE_PATH.read_text(encoding="utf-8"))
            return data.get("errors", [])
        except Exception:
            pass
    return []

def diagnose_job_log(services, log_text: str) -> dict[str, Any] | None:
    if not log_text:
        return None
    errors = services.load_error_database()
    for rule in errors:
        for pattern in rule.get("patterns", []):
            try:
                if re.search(pattern, log_text, re.IGNORECASE):
                    return {
                        "matched": True,
                        "errorId": rule.get("id"),
                        "category": rule.get("category", "general"),
                        "title": rule.get("title", {}),
                        "cause": rule.get("cause", {}),
                        "solution": rule.get("solution", {}),
                        "fixAction": rule.get("fix_action"),
                        "fixButtonLabel": rule.get("fix_button_label", {}),
                    }
            except re.error:
                continue
    return {
        "matched": False,
        "errorId": "UNKNOWN_ERROR",
        "category": "unknown",
        "title": {
            "pl": "Nierozpoznany błąd",
            "en": "Unrecognized Error",
        },
        "cause": {
            "pl": "Treść błędu nie została jeszcze skatalogowana w lokalnej bazie wiedzy.",
            "en": "This error output has not been cataloged in the local knowledge base yet.",
        },
        "solution": {
            "pl": "Możesz przekazać treść logu do Asystenta AI w celu automatycznej analizy lub dodać regułę do docs/ERROR_KNOWLEDGE_BASE.md.",
            "en": "You can ask the AI Assistant to analyze this log or add a new rule to docs/ERROR_KNOWLEDGE_BASE.md.",
        },
        "fixAction": None,
        "fixButtonLabel": None,
        "canAskAi": True,
    }

#!/usr/bin/env python3
"""Local-only web interface for Mini Order Platform Control Center.

The server deliberately has no external dependencies. It binds to loopback,
executes only allow-listed Control Center commands and keeps runtime output in
gitIgnoreOwn. Interactive operations remain available through a PTY session.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

try:
    import pty
    import select
except (ImportError, ModuleNotFoundError):  # Windows syntax/HTTP smoke tests only.
    pty = None  # type: ignore[assignment]
    select = None  # type: ignore[assignment]


WEB_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = WEB_ROOT.parents[2]
CONTROL_SCRIPT = PROJECT_ROOT / "scripts" / "control-center" / "control-center.sh"
RUNTIME_ROOT = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "control-center-web"
JOB_ROOT = RUNTIME_ROOT / "jobs"
ACTIVE_PROFILE_FILE = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "active-profile"
GATEWAY_API = "http://127.0.0.1:18080/api"
ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
MAX_BODY = 1_048_576

SERVICES = [
    ("gateway", "API Gateway", 18080),
    ("product", "Product", 18081),
    ("order", "Order", 18082),
    ("inventory", "Inventory", 18083),
    ("payment", "Payment", 18084),
    ("notification", "Notification", 18086),
    ("customer", "Customer", 18087),
    ("delivery", "Delivery", 18088),
    ("promotion", "Promotion", 18089),
    ("wishlist", "Wishlist", 18090),
    ("review", "Review", 18091),
    ("recommendation", "Recommendation", 18092),
    ("chat", "Chat", 18093),
    ("returns", "Returns", 18094),
    ("billing", "Billing", 18095),
]

SERVICE_OPTIONS = [item[0] for item in SERVICES]
DB_OPTIONS = SERVICE_OPTIONS[1:]

SECRET_EDIT_FIELDS = [
    {"name": "PRODUCT_DB_PASSWORD", "label": "Product DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "ORDER_DB_PASSWORD", "label": "Order DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "INVENTORY_DB_PASSWORD", "label": "Inventory DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "PAYMENT_DB_PASSWORD", "label": "Payment DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "NOTIFICATION_DB_PASSWORD", "label": "Notification DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "CUSTOMER_DB_PASSWORD", "label": "Customer DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "DELIVERY_DB_PASSWORD", "label": "Delivery DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "PROMOTION_DB_PASSWORD", "label": "Promotion DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "WISHLIST_DB_PASSWORD", "label": "Wishlist DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "REVIEW_DB_PASSWORD", "label": "Review DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "RECOMMENDATION_DB_PASSWORD", "label": "Recommendation DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "CHAT_DB_PASSWORD", "label": "Chat DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "RETURNS_DB_PASSWORD", "label": "Returns DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "BILLING_DB_PASSWORD", "label": "Billing DB — hasło", "type": "password", "section": "Bazy danych"},
    {"name": "GRAFANA_ADMIN_PASSWORD", "label": "Grafana — hasło administratora", "type": "password", "section": "Usługi lokalne"},
    {"name": "GRAFANA_ADMIN_USER", "label": "Grafana — użytkownik administratora", "type": "text", "section": "Usługi lokalne"},
    {"name": "MAIL_HOST", "label": "SMTP — host", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_PORT", "label": "SMTP — port", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_USERNAME", "label": "SMTP — użytkownik", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_PASSWORD", "label": "SMTP — hasło", "type": "password", "section": "Mailpit / SMTP"},
    {"name": "MAIL_FROM", "label": "SMTP — adres nadawcy", "type": "email", "section": "Mailpit / SMTP"},
    {"name": "MAIL_SMTP_AUTH", "label": "SMTP — uwierzytelnianie", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP"},
    {"name": "MAIL_SMTP_STARTTLS_ENABLE", "label": "SMTP — STARTTLS", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP"},
    {"name": "CHAT_SUPPORT_EMAIL", "label": "Czat — adres wsparcia", "type": "email", "section": "Kontakt"},
    {"name": "CUSTOMER_JWT_SECRET", "label": "Legacy JWT secret", "type": "password", "section": "Zaawansowane", "note": "Zmiana może unieważnić tokeny w trybie legacy. Rotację kluczy RSA wykonuj osobną operacją JWT."},
]
SECRET_EDIT_NAMES = {field["name"] for field in SECRET_EDIT_FIELDS}
SECRET_SETUP_DB_FIELDS = [
    ("PRODUCT_DB_PASSWORD", "Product DB — hasło"),
    ("ORDER_DB_PASSWORD", "Order DB — hasło"),
    ("INVENTORY_DB_PASSWORD", "Inventory DB — hasło"),
    ("PAYMENT_DB_PASSWORD", "Payment DB — hasło"),
    ("NOTIFICATION_DB_PASSWORD", "Notification DB — hasło"),
    ("CUSTOMER_DB_PASSWORD", "Customer DB — hasło"),
    ("DELIVERY_DB_PASSWORD", "Delivery DB — hasło"),
    ("PROMOTION_DB_PASSWORD", "Promotion DB — hasło"),
    ("WISHLIST_DB_PASSWORD", "Wishlist DB — hasło"),
    ("REVIEW_DB_PASSWORD", "Review DB — hasło"),
    ("RECOMMENDATION_DB_PASSWORD", "Recommendation DB — hasło"),
    ("CHAT_DB_PASSWORD", "Chat DB — hasło"),
    ("RETURNS_DB_PASSWORD", "Returns DB — hasło"),
    ("BILLING_DB_PASSWORD", "Billing DB — hasło"),
]
SECRET_SETUP_FIELDS = [
    *({"name": name, "label": label, "type": "password", "section": "Bazy danych", "required": True} for name, label in SECRET_SETUP_DB_FIELDS),
    {"name": "CUSTOMER_JWT_SECRET", "label": "Legacy JWT secret (Base64)", "type": "password", "section": "Bezpieczeństwo", "required": True, "note": "Minimum 32 bajty po dekodowaniu Base64."},
    {"name": "GRAFANA_ADMIN_PASSWORD", "label": "Grafana — hasło administratora", "type": "password", "section": "Usługi lokalne", "required": True},
    {"name": "GRAFANA_ADMIN_USER", "label": "Grafana — użytkownik administratora", "type": "text", "section": "Usługi lokalne", "default": "admin"},
    {"name": "MAIL_HOST", "label": "SMTP — host", "type": "text", "section": "Mailpit / SMTP", "default": "127.0.0.1"},
    {"name": "MAIL_PORT", "label": "SMTP — port", "type": "text", "section": "Mailpit / SMTP", "default": "11025"},
    {"name": "MAIL_USERNAME", "label": "SMTP — użytkownik", "type": "text", "section": "Mailpit / SMTP"},
    {"name": "MAIL_PASSWORD", "label": "SMTP — hasło", "type": "password", "section": "Mailpit / SMTP"},
    {"name": "MAIL_FROM", "label": "SMTP — adres nadawcy", "type": "email", "section": "Mailpit / SMTP"},
    {"name": "MAIL_SMTP_AUTH", "label": "SMTP — uwierzytelnianie", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP", "default": "false"},
    {"name": "MAIL_SMTP_STARTTLS_ENABLE", "label": "SMTP — STARTTLS", "type": "select", "options": ["false", "true"], "section": "Mailpit / SMTP", "default": "false"},
    {"name": "CHAT_SUPPORT_EMAIL", "label": "Czat — adres wsparcia", "type": "email", "section": "Kontakt"},
]
SECRET_SETUP_NAMES = {field["name"] for field in SECRET_SETUP_FIELDS}
SECRET_SETUP_REQUIRED_NAMES = {field["name"] for field in SECRET_SETUP_FIELDS if field.get("required")}
SECRET_PROFILES = {"dev", "test"}
SECRET_UPDATE_LOCK = threading.Lock()


def f(name: str, label: str, kind: str = "text", **extra: Any) -> dict[str, Any]:
    value = {"name": name, "label": label, "type": kind}
    value.update(extra)
    return value


def action(
    action_id: str,
    title: str,
    group: str,
    description: str,
    command: str,
    fields: list[dict[str, Any]] | None = None,
    *,
    danger: bool = False,
    stdin_fields: list[str] | None = None,
    secret_profile: str | None = None,
    secret_mode: str = "secret-edit",
) -> dict[str, Any]:
    value = {
        "id": action_id,
        "title": title,
        "group": group,
        "description": description,
        "command": command,
        "fields": fields or [],
        "danger": danger,
        "stdinFields": stdin_fields or [],
    }
    if secret_profile:
        value["kind"] = secret_mode
        value["profile"] = secret_profile
    return value


def build_actions() -> list[dict[str, Any]]:
    profile = f("profile", "Profil", "select", options=["dev", "test"], default="dev")
    service = f("service", "Serwis", "select", options=SERVICE_OPTIONS, default="product")
    database = f("database", "Baza / serwis", "select", options=["all", *DB_OPTIONS], default="all")
    limit = f("limit", "Limit", "number", default=50, min=1, max=1000)
    result = [
        action("environment-check", "Kontrola przed startem", "Start i diagnostyka", "Sprawdza WSL, JDK, Docker, sekrety i pliki projektu.", "environment-check"),
        action("doctor", "Doctor środowiska", "Start i diagnostyka", "Pełna diagnostyka działającego środowiska.", "doctor"),
        action("github-check", "Kontrola przed GitHub", "Start i diagnostyka", "Szuka sekretów i prywatnych artefaktów przed publikacją.", "github-check"),
        action("platform-info", "Informacje o platformie", "Start i diagnostyka", "Pokazuje wykryty system i adapter uruchomieniowy.", "platform-info"),
        action("profile-show", "Aktywny profil", "Profile i sekrety", "Pokazuje profil dev/test używany przez Centralę.", "profile-show"),
        action("profile-set", "Zmień profil", "Profile i sekrety", "Ustawia aktywny profil lokalny.", "profile-set", [profile]),
        action("language-show", "Język menu terminalowego", "Ustawienia", "Pokazuje język klasycznego menu Centrali.", "language-show"),
        action("language-set", "Zmień język menu terminalowego", "Ustawienia", "Ustawia polski albo angielski język klasycznego menu Centrali.", "language-set", [f("language", "Język", "select", options=["pl", "en"], default="pl")]),
        action("secrets-status", "Status skarbca", "Profile i sekrety", "Sprawdza SOPS, age, klucze i zaszyfrowane pliki bez ujawniania wartości.", "secrets-status"),
        action("secrets-validate", "Waliduj sekrety", "Profile i sekrety", "Sprawdza komplet wymaganych nazw sekretów.", "secrets-validate", [profile]),
        action("secrets-init", "Inicjalizuj klucze age", "Profile i sekrety", "Tworzy brakujące klucze profili dev/test.", "secrets-init", danger=True),
        action("secrets-setup-dev", "Utwórz sekrety DEV", "Profile i sekrety", "Tworzy pierwszy zaszyfrowany plik sekretów profilu dev bez logowania wartości.", "secrets-setup-web", danger=True, secret_profile="dev", secret_mode="secret-setup"),
        action("secrets-setup-test", "Utwórz sekrety TEST", "Profile i sekrety", "Tworzy pierwszy zaszyfrowany plik sekretów profilu test bez logowania wartości.", "secrets-setup-web", danger=True, secret_profile="test", secret_mode="secret-setup"),
        action("secrets-ensure-auth", "Uzupełnij sekret JWT", "Profile i sekrety", "Generuje brakujący materiał JWT bez jego wyświetlania.", "secrets-ensure-auth", [profile], danger=True),
        action("secrets-edit-dev", "Edytuj sekrety DEV", "Profile i sekrety", "Otwiera bezpieczny formularz aktualizacji sekretów profilu dev; wartości pozostają ukryte.", "secrets-update-web", danger=True, secret_profile="dev"),
        action("secrets-edit-test", "Edytuj sekrety TEST", "Profile i sekrety", "Otwiera bezpieczny formularz aktualizacji sekretów profilu test; wartości pozostają ukryte.", "secrets-update-web", danger=True, secret_profile="test"),
        action("jwt-status", "Status JWT", "Bezpieczeństwo", "Pokazuje konfigurację JWT bez tokenów i kluczy prywatnych.", "jwt-status"),
        action("jwt-rotate", "Rotuj klucze JWT", "Bezpieczeństwo", "Generuje nowy klucz RSA i zachowuje poprzedni klucz publiczny.", "jwt-rotate", [profile], danger=True),
        action("jwt-smoke", "Smoke JWT", "Bezpieczeństwo", "Sprawdza login, /me, refresh i logout.", "jwt-smoke", [f("email", "E-mail (opcjonalnie)", required=False)]),
        action("audit-list", "Dziennik audytowy", "Bezpieczeństwo", "Pokazuje zdarzenia bezpieczeństwa Customer Service.", "audit-list", [limit]),
        action("password-reset-request", "Reset hasła", "Bezpieczeństwo", "Wysyła link resetu hasła do Mailpit.", "password-reset-request", [f("email", "E-mail", "email", required=True)]),
        action("infra-up", "Uruchom infrastrukturę", "Infrastruktura", "Uruchamia bazy, Redis i Mailpit z aktywnymi sekretami.", "infra-up"),
        action("infra-down", "Zatrzymaj infrastrukturę", "Infrastruktura", "Zatrzymuje lokalne kontenery infrastruktury.", "infra-down", danger=True),
        action("infra-status", "Status Docker Compose", "Infrastruktura", "Pokazuje wszystkie kontenery projektu.", "infra-status"),
        action("infra-logs", "Logi infrastruktury", "Infrastruktura", "Pokazuje ostatnie logi wszystkich kontenerów infrastruktury.", "infra-logs"),
        action("db-list", "Mapa baz i portów", "Bazy danych", "Pokazuje właścicieli, nazwy i porty baz PostgreSQL.", "db-list"),
        action("db-up", "Uruchom bazę/bazy", "Bazy danych", "Uruchamia wskazaną bazę lub wszystkie bazy.", "db-up", [database]),
        action("db-down", "Zatrzymaj bazę/bazy", "Bazy danych", "Zatrzymuje wskazaną bazę lub wszystkie bazy.", "db-down", [database], danger=True),
        action("db-status", "Status baz", "Bazy danych", "Pokazuje stan kontenerów PostgreSQL.", "db-status", [database]),
        action("db-logs", "Logi bazy", "Bazy danych", "Pokazuje ostatnie logi wybranej bazy.", "db-logs", [database]),
        action("db-backup", "Wykonaj backup", "Backupy", "Tworzy szyfrowany backup jednej lub wszystkich baz.", "db-backup", [database]),
        action("db-backup-list", "Lista backupów", "Backupy", "Pokazuje dostępne backupy aktywnego profilu.", "db-backup-list", [database]),
        action("db-restore-test", "Test odtworzenia", "Backupy", "Odtwarza backup do bazy testowej i weryfikuje wynik.", "db-restore-test", [database], danger=True),
        action("db-backup-prune", "Retencja backupów", "Backupy", "Usuwa backupy starsze niż podana liczba dni.", "db-backup-prune", [f("days", "Liczba dni", "number", default=14, min=1)], danger=True),
        action("backup-key-init", "Klucz backupów", "Backupy", "Przygotowuje klucz age do szyfrowania kopii.", "backup-key-init", [profile], danger=True),
        action("backup-schedule-install", "Harmonogram backupu", "Backupy", "Instaluje codzienny harmonogram w WSL.", "backup-schedule-install", [f("hour", "Godzina UTC", "number", default=2, min=0, max=23)], danger=True),
        action("backup-schedule-status", "Status harmonogramu", "Backupy", "Pokazuje konfigurację automatycznych backupów.", "backup-schedule-status"),
        action("backup-schedule-remove", "Usuń harmonogram", "Backupy", "Usuwa wpis automatycznego backupu.", "backup-schedule-remove", danger=True),
        action("inventory-reset", "Reset magazynu demo", "Dane demonstracyjne", "Przywraca bazowe ilości magazynowe.", "inventory-reset", danger=True),
        action("demo-reset", "Reset danych demo", "Dane demonstracyjne", "Czyści dane transakcyjne i odtwarza demonstrację.", "demo-reset", danger=True),
        action("app-run", "Uruchom serwis", "Aplikacje", "Uruchamia jeden mikroserwis przez Maven.", "app-run", [service, profile]),
        action("app-run-all", "Uruchom wszystkie usługi", "Aplikacje", "Uruchamia 15 usług w tle z aktywnym profilem.", "app-run-all"),
        action("app-stop-all", "Zatrzymaj usługi", "Aplikacje", "Zatrzymuje procesy uruchomione przez Centralę.", "app-stop-all", danger=True),
        action("app-status-all", "Status procesów", "Aplikacje", "Pokazuje PID, port i stan wszystkich usług.", "app-status-all"),
        action("app-logs", "Logi mikroserwisu", "Aplikacje", "Pokazuje ostatni log wskazanej usługi.", "app-logs", [service]),
        action("health", "Dostępność usług", "Aplikacje", "Sprawdza endpointy wszystkich usług.", "health", [f("service", "Serwis", "select", options=["all", *SERVICE_OPTIONS], default="all")]),
        action("frontend-up", "Uruchom sklep", "Frontend", "Uruchamia lokalny frontend na porcie 18070.", "frontend-up"),
        action("frontend-down", "Zatrzymaj sklep", "Frontend", "Zatrzymuje lokalny frontend.", "frontend-down", danger=True),
        action("frontend-status", "Status frontendu", "Frontend", "Sprawdza proces i adres sklepu.", "frontend-status"),
        action("frontend-logs", "Logi frontendu", "Frontend", "Pokazuje log serwera statycznego.", "frontend-logs"),
        action("broker-up", "Uruchom Redis", "Redis i zdarzenia", "Uruchamia Event Broker.", "broker-up"),
        action("broker-down", "Zatrzymaj Redis", "Redis i zdarzenia", "Zatrzymuje Event Broker.", "broker-down", danger=True),
        action("broker-status", "Status Redis", "Redis i zdarzenia", "Sprawdza kontener i PING.", "broker-status"),
        action("broker-logs", "Logi Redis", "Redis i zdarzenia", "Pokazuje logi brokera.", "broker-logs"),
        action("stream-info", "Informacje o streamie", "Redis i zdarzenia", "Pokazuje streamy i grupy konsumentów.", "stream-info"),
        action("stream-read", "Ostatnie zdarzenia", "Redis i zdarzenia", "Pokazuje ostatnie wpisy streamu.", "stream-read", [limit]),
        action("broker-reset-group", "Reset consumer group", "Redis i zdarzenia", "Usuwa grupę konsumenta bez kasowania streamu.", "broker-reset-group", [f("group", "Nazwa grupy", default="notification-service")], danger=True),
        action("monitoring-up", "Uruchom monitoring", "Monitoring i DLQ", "Uruchamia Prometheus i Grafanę.", "monitoring-up"),
        action("monitoring-down", "Zatrzymaj monitoring", "Monitoring i DLQ", "Zatrzymuje Prometheus i Grafanę.", "monitoring-down", danger=True),
        action("monitoring-status", "Status monitoringu", "Monitoring i DLQ", "Pokazuje kontenery i endpointy monitoringu.", "monitoring-status"),
        action("monitoring-logs", "Logi monitoringu", "Monitoring i DLQ", "Pokazuje ostatnie logi Prometheusa i Grafany.", "monitoring-logs"),
        action("monitoring-urls", "Adresy monitoringu", "Monitoring i DLQ", "Pokazuje adresy Prometheus i Grafany.", "monitoring-urls"),
        action("dlq-list", "Wpisy Dead Letter Queue", "Monitoring i DLQ", "Pokazuje niewysłane powiadomienia.", "dlq-list"),
        action("dlq-replay", "Ponów wpis DLQ", "Monitoring i DLQ", "Ponawia wysyłkę wskazanego wpisu.", "dlq-replay", [f("id", "ID wpisu", "number", required=True, min=1)], danger=True),
        action("mailpit-up", "Uruchom Mailpit", "Mailpit", "Uruchamia testowy serwer SMTP i skrzynkę.", "mailpit-up"),
        action("mailpit-down", "Zatrzymaj Mailpit", "Mailpit", "Zatrzymuje Mailpit.", "mailpit-down", danger=True),
        action("mailpit-status", "Status Mailpit", "Mailpit", "Sprawdza kontener i panel poczty.", "mailpit-status"),
        action("mailpit-logs", "Logi Mailpit", "Mailpit", "Pokazuje ostatnie logi lokalnego serwera poczty.", "mailpit-logs"),
        action("mailpit-list", "Ostatnie wiadomości", "Mailpit", "Pokazuje wiadomości w skrzynce testowej.", "mailpit-list", [limit]),
        action("mailpit-search", "Szukaj wiadomości", "Mailpit", "Wyszukuje wiadomości według zapytania Mailpit.", "mailpit-search", [f("query", "Zapytanie", required=True)]),
        action("mailpit-smoke", "Smoke e-mail", "Mailpit", "Sprawdza Notification Service → SMTP → Mailpit.", "mailpit-smoke"),
        action("mailpit-send", "Wyślij e-mail testowy", "Mailpit", "Wysyła własną wiadomość przez Notification Service.", "mailpit-send", [f("recipient", "Odbiorca", "email", default="mailpit-test@minitech.local"), f("subject", "Temat", default="Test z Control Center"), f("message", "Treść", "textarea", default="Wiadomość testowa z Control Center")], stdin_fields=["recipient", "subject", "message"]),
        action("mailpit-clear", "Wyczyść Mailpit", "Mailpit", "Usuwa wszystkie wiadomości z lokalnej skrzynki testowej.", "mailpit-clear", danger=True),
        action("orders-list", "Wszystkie zamówienia", "Operacje sklepu", "Pokazuje ID, status, kwotę, e-mail, fakturę i pozycje.", "orders-list", [limit]),
        action("promotion-codes", "Kody rabatowe", "Operacje sklepu", "Pokazuje kody albo sprawdza konkretny kod.", "promotion-codes", [f("code", "Kod (puste = lista)", required=False)]),
        action("promotion-code-create", "Utwórz kod rabatowy", "Operacje sklepu", "Tworzy regułę rabatową dla dev/test.", "promotion-code-create", [f("code", "Kod (puste = automatyczny)"), f("description", "Opis", default="Kod rabatowy MiniTech Store"), f("discount", "Rabat %", "number", default=10, min=1, max=100), f("limit", "Limit użyć (puste = bez limitu)"), f("minimum", "Minimalna wartość zamówienia", "number", default=0, min=0), f("maximum", "Maksymalna kwota rabatu (puste = bez limitu)"), f("from", "Ważny od", "date"), f("to", "Ważny do", "date", default="2099-12-31"), f("active", "Aktywny", "select", options=["true", "false"], default="true")], stdin_fields=["code", "description", "discount", "limit", "minimum", "maximum", "from", "to", "active"]),
        action("review-pending", "Opinie do moderacji", "Operacje sklepu", "Pokazuje oczekujące opinie klientów.", "review-pending"),
        action("review-moderate", "Moderuj opinię", "Operacje sklepu", "Publikuje albo odrzuca opinię.", "review-moderate", [f("id", "ID opinii", "number", required=True, min=1), f("status", "Status", "select", options=["PUBLISHED", "REJECTED"], default="PUBLISHED"), f("note", "Notatka", "textarea")], danger=True),
        action("delivery-tracking", "Śledzenie dostawy", "Operacje sklepu", "Pokazuje przewoźnika, numer i status przesyłki.", "delivery-tracking", [f("order", "ID zamówienia", "number", required=True, min=1)]),
        action("delivery-tracking-status", "Zmień status dostawy", "Operacje sklepu", "Zmienia status przesyłki w profilu dev/test.", "delivery-tracking-status", [f("order", "ID zamówienia", "number", required=True, min=1), f("status", "Status", "select", options=["LABEL_CREATED", "READY_FOR_PICKUP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "PICKED_UP", "CANCELLED"])], danger=True),
        action("returns-list", "Zwroty i reklamacje", "Operacje sklepu", "Pokazuje zgłoszenia klientów.", "returns-list"),
        action("returns-status", "Zmień status zgłoszenia", "Operacje sklepu", "Aktualizuje zwrot lub reklamację.", "returns-status", [f("id", "ID zgłoszenia", "number", required=True, min=1), f("status", "Status", "select", options=["UNDER_REVIEW", "APPROVED", "REJECTED", "COMPLETED"]), f("note", "Notatka", "textarea")], danger=True),
        action("invoice-show", "Pokaż fakturę", "Operacje sklepu", "Pobiera dane faktury dla zamówienia.", "invoice-show", [f("order", "ID zamówienia", "number", required=True, min=1)]),
        action("invoice-pdf", "Pobierz PDF faktury", "Operacje sklepu", "Zapisuje PDF w prywatnym katalogu runtime.", "invoice-pdf", [f("order", "ID zamówienia", "number", required=True, min=1)]),
    ]

    tests = [
        ("smoke", "Smoke API Gateway", "Szybko sprawdza katalog przez publiczny punkt wejścia."),
        ("app-verify", "App Verify", "Sprawdza zakup, płatność, magazyn i e-mail."),
        ("verify-mvp", "Pełna weryfikacja MVP", "Uruchamia rozszerzoną weryfikację kompletnego przepływu MVP."),
        ("delivery-smoke", "Smoke Delivery Service", "Sprawdza wycenę, termin i śledzenie dostawy."),
        ("promotion-smoke", "Smoke Promotion Service", "Sprawdza aktywne promocje i reguły rabatowe."),
        ("contract-test", "Contract test API v1", "Porównuje publiczny kontrakt z oczekiwanym schematem."),
        ("security-test", "Test bezpieczeństwa API", "Sprawdza podstawowe zabezpieczenia endpointów."),
        ("zap-dast", "OWASP ZAP DAST", "Uruchamia skaner dynamiczny API Gateway."),
        ("load-test", "Test obciążeniowy", "Wysyła kontrolowany ruch do lokalnego API."),
        ("e2e", "Pełny E2E API", "Rejestracja, JWT, zakup, płatność i Mailpit."),
        ("ui-test-status", "Status Playwright", "Sprawdza Node, pakiety i przeglądarki."),
        ("ui-test-install", "Instalacja Playwright", "Instaluje zależności testów i Chromium."),
        ("ui-test-smoke", "Playwright Smoke", "Sprawdza krytyczne ekrany sklepu."),
        ("ui-test", "Regresja UI Chromium", "Uruchamia pełny zestaw UI z mock API."),
        ("ui-test-system", "Systemowy checkout UI", "Testuje frontend z prawdziwymi usługami."),
        ("ui-test-browsers", "UI wiele przeglądarek", "Chromium, Firefox, WebKit i mobile."),
        ("ui-test-headed", "UI headed", "Uruchamia test z widoczną przeglądarką."),
        ("ui-test-debug", "Playwright Inspector", "Uruchamia tryb debugowania testów."),
        ("ui-test-report", "Raport Playwright", "Otwiera wygenerowany raport HTML."),
    ]
    result.extend(action(command, title, "Testy", description, command) for command, title, description in tests)
    result.extend([
        action("resilience-test", "Chaos / Test odporności", "Testy", "Symuluje awarię wybranego mikroserwisu i sprawdza izolację awarii oraz degradację.", "resilience-test", [f("service", "Wybrany mikroserwis", "select", options=["payment-service", "inventory-service", "product-service", "order-service", "customer-service", "delivery-service", "promotion-service", "notification-service", "chat-service", "returns-service", "billing-service"], default="payment-service")]),
        action("app-test", "Testy Maven", "Budowanie", "Uruchamia testy serwisu albo monorepo.", "app-test", [f("service", "Zakres", "select", options=["all", *SERVICE_OPTIONS], default="all")]),
        action("app-package", "Pakietowanie Maven", "Budowanie", "Buduje jeden serwis albo całe monorepo.", "app-package", [f("service", "Zakres", "select", options=["all", *SERVICE_OPTIONS], default="all")]),
        action("images-build", "Obrazy Docker", "Budowanie", "Buduje lokalne obrazy wszystkich aplikacji.", "images-build"),
        action("api-docs-urls", "OpenAPI / Swagger", "Dokumentacja API", "Pokazuje adresy dokumentacji usług.", "api-docs-urls"),
        action("resilience-status", "Odporność usług", "Dokumentacja API", "Pokazuje Retry, Circuit Breaker i Bulkhead.", "resilience-status"),
        action("prod-status", "Status profilu prod", "Prod — tylko walidacja", "Wyjaśnia, dlaczego prod jest zablokowany.", "prod-status"),
        action("k8s-validate", "Waliduj Kubernetes", "Prod — tylko walidacja", "Sprawdza manifesty bez wdrażania.", "k8s-validate"),
        action("k8s-status", "Status Kubernetes", "Prod — tylko walidacja", "Pokazuje stan klastra, jeśli jest skonfigurowany.", "k8s-status"),
        action("openbao-validate", "Waliduj OpenBao/TLS", "Prod — tylko walidacja", "Sprawdza pliki przygotowania produkcyjnego.", "openbao-validate"),
        action("openbao-status", "Status OpenBao", "Prod — tylko walidacja", "Sprawdza endpoint bez ujawniania sekretów.", "openbao-status"),
        action("tls-status", "Status TLS", "Prod — tylko walidacja", "Sprawdza certyfikat wskazanego hosta.", "tls-status"),
    ])
    return result


ACTIONS = build_actions()
ACTION_BY_ID = {item["id"]: item for item in ACTIONS}


@dataclass
class Job:
    id: str
    action_id: str
    title: str
    command: list[str]
    log_path: Path
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    status: str = "queued"
    exit_code: int | None = None

    def public(self, include_output: bool = False) -> dict[str, Any]:
        value = {
            "id": self.id,
            "actionId": self.action_id,
            "title": self.title,
            "command": " ".join(self.command[2:]),
            "createdAt": self.created_at,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "status": self.status,
            "exitCode": self.exit_code,
        }
        if include_output:
            try:
                value["output"] = ANSI_RE.sub("", self.log_path.read_text(encoding="utf-8", errors="replace"))[-120_000:]
            except FileNotFoundError:
                value["output"] = ""
        return value


@dataclass
class TerminalSession:
    id: str
    pid: int
    fd: int
    output: bytearray = field(default_factory=bytearray)
    alive: bool = True
    lock: threading.Lock = field(default_factory=threading.Lock)


JOBS: dict[str, Job] = {}
TERMINALS: dict[str, TerminalSession] = {}
STATE_LOCK = threading.Lock()
CSRF_TOKEN = secrets.token_urlsafe(32)


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def active_profile() -> str:
    try:
        value = ACTIVE_PROFILE_FILE.read_text(encoding="utf-8").strip()
        return value if value in {"dev", "test"} else "dev"
    except FileNotFoundError:
        return "dev"


def secret_root_directory() -> Path:
    configured = os.environ.get("MINI_ORDER_PLATFORM_SECRETS_DIR")
    if configured:
        return Path(configured).expanduser()
    config_home = os.environ.get("XDG_CONFIG_HOME")
    config_directory = Path(config_home).expanduser() if config_home else Path.home() / ".config"
    return config_directory / "mini-order-platform"


def public_secret_profiles() -> dict[str, dict[str, bool]]:
    root = secret_root_directory()
    return {
        profile: {
            "key": (root / "keys" / f"{profile}.agekey").is_file(),
            "file": (root / "secrets" / f"{profile}.env.sops").is_file(),
        }
        for profile in sorted(SECRET_PROFILES)
    }


def update_profile_secrets(profile: str, values: Any) -> list[str]:
    if profile not in SECRET_PROFILES:
        raise ValueError("Edycja sekretów jest dostępna wyłącznie dla profilu dev albo test.")
    if not isinstance(values, dict):
        raise ValueError("Nieprawidłowe dane formularza sekretów.")

    unknown = set(values) - SECRET_EDIT_NAMES
    if unknown:
        raise ValueError("Formularz zawiera niedozwolone pole sekretu.")

    encoded_lines: list[str] = []
    updated_names: list[str] = []
    for field in SECRET_EDIT_FIELDS:
        name = field["name"]
        if name not in values:
            continue
        value = values[name]
        if not isinstance(value, str):
            raise ValueError("Wartości sekretów muszą być tekstem.")
        # An empty field means "leave the existing value unchanged". This
        # prevents an accidental blank submission from deleting a secret.
        if value == "":
            continue
        if len(value) > 4096 or any(character in value for character in ("\x00", "\n", "\r")):
            raise ValueError(f"Wartość pola {name} jest za długa albo zawiera niedozwolone znaki.")
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        encoded_lines.append(f"{name}\t{encoded}\n")
        updated_names.append(name)

    if not encoded_lines:
        raise ValueError("Wprowadź co najmniej jedną nową wartość. Puste pola pozostają bez zmian.")

    command = ["bash", str(CONTROL_SCRIPT), "secrets-update-web", profile]
    environment = os.environ.copy()
    environment["TERM"] = "dumb"
    try:
        with SECRET_UPDATE_LOCK:
            result = subprocess.run(
                command,
                cwd=PROJECT_ROOT,
                input="".join(encoded_lines),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
                env=environment,
                timeout=60,
                check=False,
            )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Nie udało się uruchomić bezpiecznej aktualizacji sekretów.") from error
    if result.returncode != 0:
        raise RuntimeError("Nie udało się zapisać sekretów. Sprawdź SOPS, age, klucz profilu i plik sekretów.")
    return updated_names


def setup_profile_secrets(profile: str, values: Any) -> list[str]:
    if profile not in SECRET_PROFILES:
        raise ValueError("Utworzenie sekretów jest dostępne wyłącznie dla profilu dev albo test.")
    if not isinstance(values, dict):
        raise ValueError("Nieprawidłowe dane formularza pierwszej konfiguracji.")

    unknown = set(values) - SECRET_SETUP_NAMES
    if unknown:
        raise ValueError("Formularz zawiera niedozwolone pole sekretu.")

    normalized: dict[str, str] = {}
    for field in SECRET_SETUP_FIELDS:
        name = field["name"]
        value = values.get(name, field.get("default", ""))
        if not isinstance(value, str):
            raise ValueError("Wartości sekretów muszą być tekstem.")
        if len(value) > 4096 or any(character in value for character in ("\x00", "\n", "\r")):
            raise ValueError(f"Wartość pola {name} jest za długa albo zawiera niedozwolone znaki.")
        if field.get("required") and value == "":
            raise ValueError(f"Pole „{field['label']}” jest wymagane.")
        if field.get("type") == "select" and value not in field.get("options", []):
            raise ValueError(f"Nieprawidłowa wartość pola „{field['label']}”.")
        normalized[name] = value

    try:
        decoded_jwt = base64.b64decode(normalized["CUSTOMER_JWT_SECRET"], validate=True)
    except (ValueError, base64.binascii.Error) as error:
        raise ValueError("Legacy JWT secret musi być poprawnym tekstem Base64.") from error
    if len(decoded_jwt) < 32:
        raise ValueError("Legacy JWT secret musi mieć minimum 32 bajty po dekodowaniu Base64.")

    encoded_lines = []
    for name, value in normalized.items():
        if value == "":
            continue
        encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
        encoded_lines.append(f"{name}\t{encoded}\n")

    command = ["bash", str(CONTROL_SCRIPT), "secrets-setup-web", profile]
    environment = os.environ.copy()
    environment["TERM"] = "dumb"
    try:
        with SECRET_UPDATE_LOCK:
            result = subprocess.run(
                command,
                cwd=PROJECT_ROOT,
                input="".join(encoded_lines),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
                env=environment,
                timeout=60,
                check=False,
            )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError("Nie udało się uruchomić kreatora pierwszej konfiguracji sekretów.") from error
    if result.returncode != 0:
        raise RuntimeError("Nie udało się utworzyć sekretów. Sprawdź SOPS, age oraz status profilu.")
    return [field["name"] for field in SECRET_SETUP_FIELDS if normalized.get(field["name"], "") != ""]


def api_json(url: str, *, method: str = "GET", payload: Any = None, timeout: float = 10) -> Any:
    data = None if payload is None else json_bytes(payload)
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        return json.loads(raw.decode("utf-8")) if raw else None


def check_service(item: tuple[str, str, int]) -> dict[str, Any]:
    alias, name, port = item
    started = time.monotonic()
    url = f"http://127.0.0.1:{port}/actuator/health"
    try:
        with urllib.request.urlopen(url, timeout=1.3) as response:
            payload = json.loads(response.read().decode("utf-8"))
            online = response.status == 200 and payload.get("status") == "UP"
            state = payload.get("status", f"HTTP {response.status}")
    except Exception:
        online, state = False, "OFFLINE"
    return {
        "alias": alias,
        "name": name,
        "port": port,
        "url": url,
        "online": online,
        "status": state,
        "latencyMs": round((time.monotonic() - started) * 1000),
    }


def run_job(job: Job, stdin_text: str) -> None:
    job.started_at = time.time()
    job.status = "running"
    JOB_ROOT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["TERM"] = "dumb"
    with job.log_path.open("w", encoding="utf-8") as output:
        output.write(f"MiniTech Control · {job.title}\n$ {' '.join(job.command)}\n\n")
        output.flush()
        try:
            process = subprocess.Popen(
                job.command,
                cwd=PROJECT_ROOT,
                stdin=subprocess.PIPE,
                stdout=output,
                stderr=subprocess.STDOUT,
                text=True,
                env=env,
            )
            process.communicate(stdin_text if stdin_text else None)
            job.exit_code = process.returncode
            job.status = "success" if process.returncode == 0 else "failed"
        except Exception as error:
            output.write(f"\nBłąd uruchomienia: {error}\n")
            job.exit_code = -1
            job.status = "failed"
        finally:
            job.finished_at = time.time()


def start_terminal() -> TerminalSession:
    if pty is None or select is None:
        raise RuntimeError("Terminal PTY jest dostępny wyłącznie po uruchomieniu panelu w WSL/Linux.")
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(PROJECT_ROOT)
        os.execvp("bash", ["bash", str(CONTROL_SCRIPT)])
    session = TerminalSession(uuid.uuid4().hex[:12], pid, fd)
    TERMINALS[session.id] = session

    def reader() -> None:
        while session.alive:
            try:
                ready, _, _ = select.select([fd], [], [], 0.5)
                if not ready:
                    try:
                        ended, _ = os.waitpid(pid, os.WNOHANG)
                        if ended:
                            session.alive = False
                    except ChildProcessError:
                        session.alive = False
                    continue
                chunk = os.read(fd, 8192)
                if not chunk:
                    session.alive = False
                    break
                with session.lock:
                    session.output.extend(chunk)
                    if len(session.output) > 500_000:
                        del session.output[:-400_000]
            except OSError:
                session.alive = False
                break

    threading.Thread(target=reader, daemon=True).start()
    return session


class Handler(SimpleHTTPRequestHandler):
    server_version = "MiniTechControl/1.0"

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, directory=str(WEB_ROOT), **kwargs)

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[control-web] {self.client_address[0]} {fmt % args}")

    def end_headers(self) -> None:
        # The panel is a local development tool. Never reuse stale JavaScript or
        # styles after it has been updated and the server has been restarted.
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        super().end_headers()

    def send_json(self, value: Any, status: int = 200) -> None:
        raw = json_bytes(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY:
            raise ValueError("Nieprawidłowy rozmiar żądania.")
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def require_token(self) -> bool:
        if self.headers.get("X-Control-Center-Token") != CSRF_TOKEN:
            self.send_json({"error": "Nieprawidłowy token lokalnej sesji."}, HTTPStatus.FORBIDDEN)
            return False
        return True

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/api/ping":
            self.send_json({"status": "UP", "profile": active_profile()})
            return
        if path == "/api/bootstrap":
            self.send_json({
                "token": CSRF_TOKEN,
                "profile": active_profile(),
                "project": str(PROJECT_ROOT),
                "actions": ACTIONS,
                "links": {
                    "store": "http://127.0.0.1:18070",
                    "mailpit": "http://127.0.0.1:18025",
                    "prometheus": "http://127.0.0.1:19090",
                    "grafana": "http://127.0.0.1:13000",
                },
            })
            return
        if path == "/api/secrets":
            self.send_json({
                "profiles": public_secret_profiles(),
                "fields": SECRET_EDIT_FIELDS,
                "setupFields": SECRET_SETUP_FIELDS,
                "warning": "Puste pola pozostawiają dotychczasowe wartości bez zmian. Aktualne wartości nie są odczytywane ani zwracane.",
            })
            return
        if path == "/api/health":
            with ThreadPoolExecutor(max_workers=len(SERVICES)) as pool:
                result = list(pool.map(check_service, SERVICES))
            self.send_json({"services": result, "online": sum(1 for item in result if item["online"])})
            return
        if path == "/api/jobs":
            with STATE_LOCK:
                jobs = sorted(JOBS.values(), key=lambda item: item.created_at, reverse=True)[:50]
            self.send_json({"jobs": [job.public() for job in jobs]})
            return
        if path.startswith("/api/jobs/"):
            job_id = path.rsplit("/", 1)[-1]
            job = JOBS.get(job_id)
            if not job:
                self.send_json({"error": "Nie znaleziono zadania."}, HTTPStatus.NOT_FOUND)
            else:
                self.send_json(job.public(include_output=True))
            return
        if path == "/api/chat/messages":
            try:
                self.send_json({"messages": api_json(f"{GATEWAY_API}/chat/messages")})
            except Exception as error:
                self.send_json({"error": f"Chat Service jest niedostępny: {error}"}, HTTPStatus.BAD_GATEWAY)
            return
        if path.startswith("/api/terminal/"):
            session_id = path.split("/")[3]
            session = TERMINALS.get(session_id)
            if not session:
                self.send_json({"error": "Sesja terminala nie istnieje."}, HTTPStatus.NOT_FOUND)
                return
            with session.lock:
                output = ANSI_RE.sub("", session.output.decode("utf-8", errors="replace"))
            self.send_json({"id": session.id, "alive": session.alive, "output": output[-150_000:]})
            return
        super().do_GET()

    def do_POST(self) -> None:  # noqa: N802
        if not self.require_token():
            return
        path = urlparse(self.path).path
        try:
            payload = self.read_json()
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/secrets/setup":
            if payload.get("confirmed") is not True:
                self.send_json({"error": "Potwierdź utworzenie zaszyfrowanego pliku sekretów."}, HTTPStatus.CONFLICT)
                return
            try:
                profile = str(payload.get("profile", ""))
                created = setup_profile_secrets(profile, payload.get("values") or {})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
                return
            except RuntimeError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_json({
                "ok": True,
                "profile": profile,
                "createdFields": created,
                "message": "Utworzono zaszyfrowany plik sekretów. Wartości nie zostały wyświetlone ani zapisane w logu zadania.",
            })
            return
        if path == "/api/secrets/update":
            if payload.get("confirmed") is not True:
                self.send_json({"error": "Potwierdź zapis zmienionych sekretów."}, HTTPStatus.CONFLICT)
                return
            try:
                updated = update_profile_secrets(str(payload.get("profile", "")), payload.get("values") or {})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
                return
            except RuntimeError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_json({
                "ok": True,
                "profile": str(payload.get("profile", "")),
                "updatedFields": updated,
                "message": "Sekrety zapisano zaszyfrowane. Ich wartości nie zostały wyświetlone.",
            })
            return
        if path == "/api/jobs":
            action_id = str(payload.get("action", ""))
            selected = ACTION_BY_ID.get(action_id)
            if not selected:
                self.send_json({"error": "Operacja nie znajduje się na białej liście."}, HTTPStatus.BAD_REQUEST)
                return
            if selected.get("kind") in {"secret-edit", "secret-setup"}:
                self.send_json({"error": "Operacja sekretów wymaga dedykowanego formularza."}, HTTPStatus.BAD_REQUEST)
                return
            if selected["danger"] and payload.get("confirmed") is not True:
                self.send_json({"error": "Operacja wymaga potwierdzenia."}, HTTPStatus.CONFLICT)
                return
            values = payload.get("values") or {}
            args: list[str] = []
            for descriptor in selected["fields"]:
                name = descriptor["name"]
                if name in selected["stdinFields"]:
                    continue
                value = str(values.get(name, descriptor.get("default", ""))).strip()
                if descriptor.get("required") and not value:
                    self.send_json({"error": f"Pole „{descriptor['label']}” jest wymagane."}, HTTPStatus.BAD_REQUEST)
                    return
                if value:
                    args.append(value)
            stdin_text = "".join(str(values.get(name, "")) + "\n" for name in selected["stdinFields"])
            job_id = uuid.uuid4().hex[:12]
            command = ["bash", str(CONTROL_SCRIPT), selected["command"], *args]
            job = Job(job_id, action_id, selected["title"], command, JOB_ROOT / f"{job_id}.log")
            with STATE_LOCK:
                JOBS[job_id] = job
            threading.Thread(target=run_job, args=(job, stdin_text), daemon=True).start()
            self.send_json(job.public(), HTTPStatus.ACCEPTED)
            return
        if path.startswith("/api/chat/") and path.endswith("/reply"):
            parts = path.strip("/").split("/")
            try:
                message_id = int(parts[2])
                message = str(payload.get("message", "")).strip()
                if not message:
                    raise ValueError("Odpowiedź nie może być pusta.")
                result = api_json(f"{GATEWAY_API}/chat/messages/{message_id}/reply", method="POST", payload={"message": message})
                self.send_json({"message": result})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            except Exception as error:
                self.send_json({"error": f"Nie udało się wysłać odpowiedzi: {error}"}, HTTPStatus.BAD_GATEWAY)
            return
        if path == "/api/terminal":
            try:
                session = start_terminal()
                self.send_json({"id": session.id, "alive": True}, HTTPStatus.CREATED)
            except Exception as error:
                self.send_json({"error": f"Nie udało się uruchomić terminala: {error}"}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if path.startswith("/api/terminal/") and path.endswith("/input"):
            session_id = path.split("/")[3]
            session = TERMINALS.get(session_id)
            if not session or not session.alive:
                self.send_json({"error": "Sesja terminala jest zamknięta."}, HTTPStatus.GONE)
                return
            data = str(payload.get("data", ""))
            os.write(session.fd, data.encode("utf-8"))
            self.send_json({"ok": True})
            return
        self.send_json({"error": "Nieznany endpoint."}, HTTPStatus.NOT_FOUND)


def shutdown_terminals() -> None:
    for session in list(TERMINALS.values()):
        if session.alive:
            try:
                os.kill(session.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass


def main() -> None:
    parser = argparse.ArgumentParser(description="MiniTech Control web panel")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18100)
    args = parser.parse_args()
    if args.host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("Panel może nasłuchiwać wyłącznie na interfejsie loopback.")
    RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"MiniTech Control: http://{args.host}:{args.port}", flush=True)
    print("Panel działa lokalnie. Ctrl+C zatrzymuje serwer.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        shutdown_terminals()
        server.server_close()


if __name__ == "__main__":
    main()

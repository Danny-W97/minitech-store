#!/usr/bin/env python3
"""Local-only web interface for Mini Order Platform Control Center.

The server deliberately has no external dependencies. It binds to loopback,
executes only allow-listed Control Center commands and keeps runtime output in
gitIgnoreOwn. Interactive operations remain available through a PTY session.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import os
import re
import secrets
import shutil
import shlex
import signal
import socket
import smtplib
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import date, datetime
from email.mime.application import MIMEApplication
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse
from panel_security import LocalRequestPolicy, PanelRejected, PanelSessions
from ai_settings import AiSettings, AiSettingsError, public_config
from stock_receipts import receive as receive_stock, StockOperationError
from invoice_corrections import issue as issue_correction, CorrectionError
from customer_list import read_page as read_customer_page, CustomerListError

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
LANGUAGE_FILE = PROJECT_ROOT / "gitIgnoreOwn" / "runtime" / "language"
DISPATCH_HISTORY_FILE = RUNTIME_ROOT / "dispatch-history.json"
GATEWAY_API = "http://127.0.0.1:18080/api"
BILLING_DIRECT_API = "http://127.0.0.1:18095"
ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
MAX_BODY = 1_048_576


import sys
import cc_actions
import cc_diagnostics
import cc_transport
import cc_secrets
import cc_health
import cc_data
import cc_events
import cc_chaos
import cc_runtime
import cc_job_logs
import cc_job_output
import cc_log_writer
import cc_ai
from cc_ai_delivery import AiDeliveryError, AiReviewRegistry
from cc_http import ControlHttpHandler

_services = sys.modules[__name__]


def positive_limit(name: str, default: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if value < 1: raise ValueError(f'{name} musi być dodatnie.')
    return value


MAX_RUNNING_JOBS = positive_limit('CC_MAX_RUNNING_JOBS', 4)
MAX_JOB_HISTORY = positive_limit('CC_MAX_JOB_HISTORY', 100)
JOB_TIMEOUT_SECONDS = positive_limit('CC_JOB_TIMEOUT_SECONDS', 21600)
MAX_TERMINALS = positive_limit('CC_MAX_TERMINALS', 4)
TERMINAL_IDLE_SECONDS = positive_limit('CC_TERMINAL_IDLE_SECONDS', 1800)
TERMINAL_LIFETIME_SECONDS = positive_limit('CC_TERMINAL_LIFETIME_SECONDS', 21600)
JOB_LOG_READ_MAX_BYTES = cc_job_output.read_limit_from_env(os.environ)

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

SECRET_SECTIONS_EN = {
    "Bazy danych": "Databases",
    "Usługi lokalne": "Local Services",
    "Mailpit / SMTP": "Mailpit / SMTP",
    "Kontakt": "Contact",
    "Zaawansowane": "Advanced",
    "Bezpieczeństwo": "Security",
}

SECRET_LABELS_EN = {
    "PRODUCT_DB_PASSWORD": "Product DB — password",
    "ORDER_DB_PASSWORD": "Order DB — password",
    "INVENTORY_DB_PASSWORD": "Inventory DB — password",
    "PAYMENT_DB_PASSWORD": "Payment DB — password",
    "NOTIFICATION_DB_PASSWORD": "Notification DB — password",
    "CUSTOMER_DB_PASSWORD": "Customer DB — password",
    "DELIVERY_DB_PASSWORD": "Delivery DB — password",
    "PROMOTION_DB_PASSWORD": "Promotion DB — password",
    "WISHLIST_DB_PASSWORD": "Wishlist DB — password",
    "REVIEW_DB_PASSWORD": "Review DB — password",
    "RECOMMENDATION_DB_PASSWORD": "Recommendation DB — password",
    "CHAT_DB_PASSWORD": "Chat DB — password",
    "RETURNS_DB_PASSWORD": "Returns DB — password",
    "BILLING_DB_PASSWORD": "Billing DB — password",
    "GRAFANA_ADMIN_PASSWORD": "Grafana — admin password",
    "GRAFANA_ADMIN_USER": "Grafana — admin username",
    "MAIL_HOST": "SMTP — host",
    "MAIL_PORT": "SMTP — port",
    "MAIL_USERNAME": "SMTP — username",
    "MAIL_PASSWORD": "SMTP — password",
    "MAIL_FROM": "SMTP — sender address",
    "MAIL_SMTP_AUTH": "SMTP — authentication",
    "MAIL_SMTP_STARTTLS_ENABLE": "SMTP — STARTTLS",
    "CHAT_SUPPORT_EMAIL": "Chat — support email",
    "CUSTOMER_JWT_SECRET": "Legacy JWT secret",
}

def get_secret_fields(lang: str = "pl") -> list[dict[str, Any]]:
    if lang == "en":
        res = []
        for item in SECRET_EDIT_FIELDS:
            d = dict(item)
            d["section"] = SECRET_SECTIONS_EN.get(d.get("section", ""), d.get("section", ""))
            d["label"] = SECRET_LABELS_EN.get(d.get("name", ""), d.get("label", ""))
            if d.get("name") == "CUSTOMER_JWT_SECRET" and "note" in d:
                d["note"] = "Changing this may invalidate tokens in legacy mode. Perform RSA key rotation using a separate JWT operation."
            res.append(d)
        return res
    return SECRET_EDIT_FIELDS

def get_secret_setup_fields(lang: str = "pl") -> list[dict[str, Any]]:
    if lang == "en":
        res = []
        for item in SECRET_SETUP_FIELDS:
            d = dict(item)
            d["section"] = SECRET_SECTIONS_EN.get(d.get("section", ""), d.get("section", ""))
            d["label"] = SECRET_LABELS_EN.get(d.get("name", ""), d.get("label", ""))
            if d.get("name") == "CUSTOMER_JWT_SECRET":
                d["label"] = "Legacy JWT secret (Base64)"
                d["note"] = "Minimum 32 bytes after Base64 decoding."
            res.append(d)
        return res
    return SECRET_SETUP_FIELDS



def f(name: str, label: str, kind: str='text', **extra: Any) -> dict[str, Any]:
    return cc_actions.f(_services, name, label, kind, **extra)


def action(action_id: str, title: str, group: str, description: str, command: str, fields: list[dict[str, Any]] | None=None, *, danger: bool=False, stdin_fields: list[str] | None=None, secret_profile: str | None=None, secret_mode: str='secret-edit', external_url: str | None=None) -> dict[str, Any]:
    return cc_actions.action(_services, action_id, title, group, description, command, fields, danger=danger, stdin_fields=stdin_fields, secret_profile=secret_profile, secret_mode=secret_mode, external_url=external_url)


def build_actions(lang: str='pl') -> list[dict[str, Any]]:
    return cc_actions.build_actions(_services, lang)


ACTIONS = build_actions("pl")
ACTION_BY_ID = {item["id"]: item for item in ACTIONS}

ERROR_DATABASE_PATH = PROJECT_ROOT / "scripts" / "control-center" / "data" / "error_database.json"


def load_error_database() -> list[dict[str, Any]]:
    return cc_diagnostics.load_error_database(_services)


def diagnose_job_log(log_text: str) -> dict[str, Any] | None:
    return cc_diagnostics.diagnose_job_log(_services, log_text)


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
    process: subprocess.Popen | None = field(default=None, repr=False, compare=False)
    retention_lease: cc_job_logs.JobLease | None = field(default=None, repr=False, compare=False)
    output_reader: cc_job_output.JobOutputReader = field(default_factory=cc_job_output.JobOutputReader, repr=False, compare=False)
    capture_reservation: cc_log_writer.Reservation | None = field(default=None, repr=False, compare=False)
    log_capture: cc_log_writer.Capture | None = field(default=None, repr=False, compare=False)

    def public(self, include_output: bool = False, lang: str | None = None) -> dict[str, Any]:
        target_lang = lang or active_language()
        title = self.title
        if self.action_id:
            try:
                for act in build_actions(target_lang):
                    if act.get("id") == self.action_id:
                        title = act.get("title") or title
                        break
            except Exception:
                pass
        value = {
            "id": self.id,
            "actionId": self.action_id,
            "title": title,
            "command": " ".join(self.command[2:]),
            "createdAt": self.created_at,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "status": self.status,
            "exitCode": self.exit_code,
            "diagnosis": None,
        }
        read_result = self.output_reader.empty(JOB_LOG_READ_MAX_BYTES, include_output)
        owned_ready = self.retention_lease is None or self.retention_lease.log_identity is not None
        if owned_ready and (include_output or self.status == 'failed'):
            read_result = self.output_reader.inspect(
                self.log_path, limit=JOB_LOG_READ_MAX_BYTES, failed=self.status == 'failed',
                include_output=include_output, diagnose=diagnose_job_log,
                rules_version=cc_job_output.file_version(ERROR_DATABASE_PATH) if self.status == 'failed' else None,
                lang=target_lang, clean=lambda text: ANSI_RE.sub('', text),
                expected_identity=self.retention_lease.log_identity if self.retention_lease is not None else None)
        value.update(read_result)
        if self.log_capture is not None:
            written = self.log_capture.snapshot()
            value['outputWrite'] = written
            if self.status == 'failed' and (written['truncated'] or written['error']) and not value['diagnosisSampled']:
                value['diagnosisSampled'] = True
                value['diagnosis'] = self.output_reader._diagnosis(value['diagnosis'], True)
            if include_output and written['truncated']:
                prefix = ('[CC: limit zapisu logu; starsza treść została pominięta.]\n' if target_lang == 'pl'
                          else '[CC: log write limit; older output was omitted.]\n')
                value['output'] = prefix + value.get('output', '')
            if include_output and written['error']:
                prefix = ('[CC: błąd zapisu logu; wyjście może być niepełne.]\n' if target_lang == 'pl'
                          else '[CC: log write error; output may be incomplete.]\n')
                value['output'] = prefix + value.get('output', '')
        return value


@dataclass
class TerminalSession:
    id: str
    pid: int
    fd: int
    output: bytearray = field(default_factory=bytearray)
    alive: bool = True
    lock: threading.Lock = field(default_factory=threading.Lock)
    created_at: float = field(default_factory=time.monotonic)
    last_activity: float = field(default_factory=time.monotonic)


JOBS: dict[str, Job] = {}
TERMINALS: dict[str, TerminalSession] = {}
STATE_LOCK = threading.Lock()
SHUTTING_DOWN = threading.Event()
LOG_RETENTION: cc_job_logs.JobLogRetention | None = None
LOG_WRITER: cc_log_writer.CaptureManager | None = None


class ResourceLimitError(RuntimeError):
    pass


def register_job(job: Job) -> None:
    return cc_runtime.register_job(_services, job)


def stop_terminal(session: TerminalSession) -> None:
    return cc_runtime.stop_terminal(_services, session)


def terminate_job(process: subprocess.Popen) -> None:
    return cc_runtime.terminate_job(_services, process)


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def active_profile() -> str:
    try:
        value = ACTIVE_PROFILE_FILE.read_text(encoding="utf-8").strip()
        return value if value in {"dev", "test"} else "dev"
    except FileNotFoundError:
        return "dev"


def active_language() -> str:
    try:
        value = LANGUAGE_FILE.read_text(encoding="utf-8").strip().lower()
        return "en" if value in {"en", "english"} else "pl"
    except FileNotFoundError:
        return "pl"


def secret_root_directory() -> Path:
    return cc_secrets.secret_root_directory(_services)


def public_secret_profiles() -> dict[str, dict[str, bool]]:
    return cc_secrets.public_secret_profiles(_services)


def update_profile_secrets(profile: str, values: Any) -> list[str]:
    return cc_secrets.update_profile_secrets(_services, profile, values)


def setup_profile_secrets(profile: str, values: Any) -> list[str]:
    return cc_secrets.setup_profile_secrets(_services, profile, values)


OPERATOR_CREDENTIAL_CACHE: dict[str, Any] = {}
OPERATOR_CREDENTIAL_LOCK = threading.Lock()


def operator_credential() -> str:
    return cc_transport.operator_credential(_services)


def platform_request(url, *args, **kwargs):
    return cc_transport.platform_request(_services, url, *args, **kwargs)


def api_json(url: str, *, method: str='GET', payload: Any=None, timeout: float=10) -> Any:
    return cc_transport.api_json(_services, url, method=method, payload=payload, timeout=timeout)


def export_params(query: dict) -> dict:
    return cc_transport.export_params(_services, query)


def list_params(query: dict) -> dict:
    return cc_transport.list_params(_services, query)


def api_page(url: str, params: dict) -> dict[str, Any]:
    return cc_transport.api_page(_services, url, params)


DOCKER_STATS_CACHE: dict[str, Any] = {"timestamp": 0.0, "data": {}}
DOCKER_STATS_LOCK = threading.Lock()


def run_docker_cli(args: list[str], timeout: float=5.0) -> tuple[int, str, str]:
    return cc_health.run_docker_cli(_services, args, timeout)


def get_docker_stats() -> dict[str, dict[str, str]]:
    return cc_health.get_docker_stats(_services)


def check_service(item: tuple[str, str, int]) -> dict[str, Any]:
    return cc_health.check_service(_services, item)


def psql_query_json(db_alias: str, query: str, timeout: float=4.0) -> list[dict[str, Any]]:
    return cc_health.psql_query_json(_services, db_alias, query, timeout)


def get_orders_data(params: dict) -> dict[str, Any]:
    return cc_data.get_orders_data(_services, params)

def generate_invoice_for_order(order_id: int) -> dict[str, Any]:
    return cc_data.generate_invoice_for_order(_services, order_id)


def get_corrections_data(invoice_id: int, params: dict) -> dict[str, Any]:
    return cc_data.get_corrections_data(_services, invoice_id, params)


def get_dispatch_corrections(params: dict) -> dict[str, Any]:
    return cc_data.get_dispatch_corrections(_services, params)

def get_invoices_data(params: dict) -> dict[str, Any]:
    return cc_data.get_invoices_data(_services, params)

def load_dispatch_history() -> list[dict[str, Any]]:
    return cc_data.load_dispatch_history(_services)


def record_dispatch(entry: dict[str, Any]) -> bool:
    return cc_data.record_dispatch(_services, entry)


def send_document_email(doc_type: str, doc_id: Any, order_id: Any, doc_number: str, recipient: str, subject: str='', custom_message: str='', buyer_name: str='') -> dict[str, Any]:
    return cc_data.send_document_email(_services, doc_type, doc_id, order_id, doc_number, recipient, subject, custom_message, buyer_name)


def get_inventory_data(params: dict) -> dict[str, Any]:
    return cc_data.get_inventory_data(_services, params)

def restock_inventory(product_id: int, quantity: int, operation_id: str) -> dict[str, Any]:
    return cc_data.restock_inventory(_services, product_id, quantity, operation_id)


def get_customers_data(params: dict) -> dict[str, Any]:
    return cc_data.get_customers_data(_services, params)

def read_redis_stream(stream_name: str='payment-events', count: int=40) -> list[dict[str, Any]]:
    return cc_events.read_redis_stream(_services, stream_name, count)


def get_outbox_events(limit: int=40) -> list[dict[str, Any]]:
    return cc_events.get_outbox_events(_services, limit)


def get_dlq_events(limit: int=40) -> list[dict[str, Any]]:
    return cc_events.get_dlq_events(_services, limit)


def replay_dlq_entry(dlq_id: int | str) -> dict[str, Any]:
    return cc_events.replay_dlq_entry(_services, dlq_id)


def parse_trace_log_line(service: str, line: str) -> dict[str, Any]:
    return cc_events.parse_trace_log_line(_services, service, line)


def trace_correlation_id(correlation_id: str) -> list[dict[str, Any]]:
    return cc_events.trace_correlation_id(_services, correlation_id)


CHAOS_TARGETS = {
    "inventory": {
        "container": "miniorderplatform-inventory-service-1",
        "label": "Inventory Service",
        "desc": "Symuluje brak odpowiedzi magazynu (blokada rezerwacji, timeouty)",
    },
    "mailpit": {
        "container": "miniorderplatform-mailpit-1",
        "label": "Mailpit SMTP",
        "desc": "Symuluje awarię serwera poczty (generuje Notification Dead Letter Queue)",
    },
    "payment": {
        "container": "miniorderplatform-payment-service-1",
        "label": "Payment Service",
        "desc": "Symuluje awarię bramki płatności (testuje Circuit Breaker i Saga rollback)",
    },
    "broker": {
        "container": "miniorderplatform-event-broker-1",
        "label": "Redis Event Broker",
        "desc": "Symuluje awarię brokera zdarzeń (testuje odkładanie do Outbox)",
    },
}


def get_chaos_status() -> dict[str, Any]:
    return cc_chaos.get_chaos_status(_services)


def apply_chaos_action(target: str, action: str) -> dict[str, Any]:
    return cc_chaos.apply_chaos_action(_services, target, action)


def run_job(job: Job, stdin_text: str) -> None:
    return cc_runtime.run_job(_services, job, stdin_text)


def start_terminal() -> TerminalSession:
    return cc_runtime.start_terminal(_services)

AI_CONFIG_FILE = RUNTIME_ROOT / "ai-config.json"
AI_SETTINGS = AiSettings(AI_CONFIG_FILE, secret_root_directory, active_profile)


def load_ai_config() -> dict[str, Any]:
    return cc_ai.load_ai_config(_services)


def save_ai_config(cfg: dict[str, Any]) -> dict[str, Any]:
    return cc_ai.save_ai_config(_services, cfg)


def public_ai_config() -> dict[str, Any]:
    return cc_ai.public_ai_config(_services)


def get_project_system_prompt(lang: str='pl') -> str:
    return cc_ai.get_project_system_prompt(_services, lang)


def query_customers_db() -> list[dict[str, Any]]:
    return cc_ai.query_customers_db(_services)


def query_reviews_db() -> list[dict[str, Any]]:
    return cc_ai.query_reviews_db(_services)


def generate_local_knowledge_reply(query: str, lang: str='pl') -> str:
    return cc_ai.generate_local_knowledge_reply(_services, query, lang)


def ai_request(url: str, data: bytes, headers: dict[str, str]):
    return cc_ai.ai_request(_services, url, data, headers)


def prepare_ai_delivery(message, history, lang='pl'):
    return cc_ai.prepare_ai_delivery(_services, message, history, lang)


def open_ai_request(request):
    return cc_ai.open_ai_request(_services, request)


def handle_ai_chat(message, history, lang='pl', approval=None, prepared=None):
    return cc_ai.handle_ai_chat(_services, message, history, lang, approval, prepared)


class Handler(ControlHttpHandler):
    services = _services


GLOBAL_SERVER: ThreadingHTTPServer | None = None


def create_server(host: str, port: int) -> ThreadingHTTPServer:
    if host not in {'127.0.0.1', 'localhost', '::1'}:
        raise ValueError('Panel może nasłuchiwać wyłącznie na interfejsie loopback.')
    class LocalServer(ThreadingHTTPServer):
        address_family = socket.AF_INET6 if host == '::1' else socket.AF_INET
        allow_reuse_address = True
        daemon_threads = True
    server = LocalServer((host, port), Handler)
    import ipaddress
    if not ipaddress.ip_address(server.server_address[0]).is_loopback:
        server.server_close()
        raise ValueError('Adres nasłuchu musi być loopback.')
    server.panel_policy = LocalRequestPolicy(server.server_port)
    server.panel_sessions = PanelSessions()
    server.ai_reviews = AiReviewRegistry()
    return server


def reload_server() -> None:
    print("Przeładowywanie serwera Centrali...", flush=True)
    time.sleep(0.1)
    shutdown_jobs()
    shutdown_terminals()
    if LOG_RETENTION is not None:
        LOG_RETENTION.stop()
    if LOG_WRITER is not None:
        LOG_WRITER.stop()
    try:
        if GLOBAL_SERVER:
            GLOBAL_SERVER.server_close()
    except Exception:
        pass
    import sys
    os.execv(sys.executable, [sys.executable] + sys.argv)


def shutdown_jobs() -> None:
    return cc_runtime.shutdown_jobs(_services)


def shutdown_terminals() -> None:
    return cc_runtime.shutdown_terminals(_services)


def main() -> None:
    global GLOBAL_SERVER, LOG_RETENTION, LOG_WRITER
    parser = argparse.ArgumentParser(description="MiniTech Control web panel")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18100)
    args = parser.parse_args()
    if args.host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("Panel może nasłuchiwać wyłącznie na interfejsie loopback.")
    retention = cc_job_logs.JobLogRetention(JOB_ROOT, cc_job_logs.LogRetentionConfig.from_env(os.environ))
    writer = cc_log_writer.CaptureManager(JOB_ROOT, cc_log_writer.WriteConfig.from_env(os.environ))
    RUNTIME_ROOT.mkdir(parents=True, exist_ok=True)
    try: load_ai_config()
    except AiSettingsError as error:
        print(f'AI: {error}', flush=True)
    server = create_server(args.host, args.port)
    GLOBAL_SERVER = server
    LOG_RETENTION = retention
    LOG_WRITER = writer
    retention.start()
    writer.start()
    if hasattr(signal, "SIGHUP"):
        signal.signal(signal.SIGHUP, lambda signum, frame: reload_server())
    display_host = f'[{args.host}]' if args.host == '::1' else args.host
    print(f"MiniTech Control: http://{display_host}:{server.server_port}", flush=True)
    print("Panel działa lokalnie. Ctrl+C zatrzymuje serwer.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        shutdown_jobs()
        shutdown_terminals()
        retention.stop()
        writer.stop()
        server.server_close()


if __name__ == "__main__":
    main()

"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler
from concurrent.futures import ThreadPoolExecutor
import json
import os
from urllib.parse import parse_qs
import re
import threading
import time
import urllib.request
from urllib.parse import urlparse
import uuid

class ControlHttpHandler(SimpleHTTPRequestHandler):
    server_version = "MiniTechControl/1.0"

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, directory=str(self.services.WEB_ROOT), **kwargs)

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(10)

    def parse_request(self) -> bool:
        if not super().parse_request(): return False
        try:
            self.panel_authority = self.server.panel_policy.validate(self.headers, self.path, self.client_address[0])
            path = urlparse(self.path).path
            public = self.command == 'GET' and path in {'/api/bootstrap', '/api/ping'}
            if path.startswith('/api/') and not public:
                tokens = self.headers.get_all('X-Control-Center-Token', [])
                self.panel_session_key = self.server.panel_sessions.authorize(tokens[0] if len(tokens) == 1 else None, self.panel_authority)
            return True
        except self.services.PanelRejected as error:
            self.close_connection = True
            self.send_json({'error': error.message, 'code': error.code}, error.status)
            return False

    def log_message(self, fmt: str, *args: Any) -> None:
        # Query strings and request lines can contain email searches or capabilities.
        category=str(getattr(self,'path','')).split('?',1)[0].split('/')[2:3]
        category=category[0] if category and category[0] in {'bootstrap','ping','session','jobs','data','events','ai','terminal','secrets','health','chat','language','diagnostics'} else 'other'
        status=str(args[1]) if len(args)>1 and re.fullmatch('[1-5][0-9]{2}',str(args[1])) else 'unknown'
        method=getattr(self,'command','OTHER');method=method if method in {'GET','POST','DELETE','PUT','OPTIONS','HEAD'} else 'OTHER'
        print(f'[control-web] method={method} category={category} status={status}')

    def end_headers(self) -> None:
        # The panel is a local development tool. Never reuse stale JavaScript or
        # styles after it has been updated and the server has been restarted.
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        super().end_headers()

    def send_json(self, value: Any, status: int = 200) -> None:
        raw = self.services.json_bytes(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self) -> dict[str, Any]:
        if len(self.headers.get_all('Content-Length', [])) != 1 or self.headers.get('Transfer-Encoding'):
            raise ValueError('Wymagana jest jednoznaczna długość żądania.')
        if self.headers.get('Content-Type', '').split(';', 1)[0].strip().lower() != 'application/json':
            raise ValueError('Wymagany jest Content-Type: application/json.')
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > self.services.MAX_BODY:
            raise ValueError("Nieprawidłowy rozmiar żądania.")
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(value, dict): raise ValueError('Wymagany jest obiekt JSON.')
        return value

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/api/data/") or path.startswith("/api/chat/"):
            headers = getattr(self, "headers", None)
            lang = headers.get("Accept-Language", "").split(",")[0].strip().lower() if headers else ""
            if not lang:
                lang = self.services.active_language()
            is_en = lang.startswith("en")
            try:
                session = self.services.api_json(f"{self.services.GATEWAY_API}/customers/operator-session", timeout=3.0)
                if not isinstance(session, dict) or session.get("role") != "OPERATOR":
                    self.send_json({"error": "Verified operator session required." if is_en else "Wymagana zweryfikowana sesja operatora."}, HTTPStatus.FORBIDDEN)
                    return
            except urllib.error.HTTPError as error:
                status = HTTPStatus.FORBIDDEN if error.code in {401, 403} else HTTPStatus.SERVICE_UNAVAILABLE
                self.send_json({"error": "Operator access cannot be verified." if is_en else "Nie można zweryfikować dostępu operatora."}, status)
                return
            except Exception:
                self.send_json({"error": "Operator access verification is unavailable." if is_en else "Weryfikacja dostępu operatora jest niedostępna."}, HTTPStatus.SERVICE_UNAVAILABLE)
                return
        if path == "/api/ping":
            self.send_json({"status": "UP", "profile": self.services.active_profile(), "language": self.services.active_language()})
            return
        if path == "/api/bootstrap":
            current_lang = self.services.active_language()
            self.send_json({
                **self.server.panel_sessions.issue(self.panel_authority),
                "profile": self.services.active_profile(),
                "language": current_lang,
                "project": str(self.services.PROJECT_ROOT),
                "actions": self.services.build_actions(current_lang),
                "links": {
                    "store": "http://127.0.0.1:18070",
                    "mailpit": "http://127.0.0.1:18025",
                    "prometheus": "http://127.0.0.1:19090",
                    "grafana": "http://127.0.0.1:13000",
                },
            })
            return
        if path == "/api/ai/config":
            try: self.send_json(self.services.public_ai_config())
            except self.services.AiSettingsError as error: self.send_json({'error': str(error), 'code': 'CC_AI_STORAGE_UNAVAILABLE'}, HTTPStatus.SERVICE_UNAVAILABLE)
            return
        if path == "/api/knowledge-base/errors":
            self.send_json({"errors": self.services.load_error_database()})
            return
        if path == "/api/secrets":
            lang = self.headers.get("Accept-Language", "").split(",")[0].strip().lower()
            if not lang:
                lang = self.services.active_language()
            is_en = lang.startswith("en")
            get_f = getattr(self.services, "get_secret_fields", lambda l="pl": self.services.SECRET_EDIT_FIELDS)
            get_sf = getattr(self.services, "get_secret_setup_fields", lambda l="pl": self.services.SECRET_SETUP_FIELDS)
            self.send_json({
                "profiles": self.services.public_secret_profiles(),
                "fields": get_f("en" if is_en else "pl"),
                "setupFields": get_sf("en" if is_en else "pl"),
                "warning": (
                    "Empty fields leave current values unchanged. Current values are neither read nor returned."
                    if is_en else
                    "Puste pola pozostawiają dotychczasowe wartości bez zmian. Aktualne wartości nie są odczytywane ani zwracane."
                ),
            })
            return
        if path == "/api/health":
            with ThreadPoolExecutor(max_workers=len(self.services.SERVICES)) as pool:
                result = list(pool.map(self.services.check_service, self.services.SERVICES))
            self.send_json({"services": result, "online": sum(1 for item in result if item["online"])})
            return
        if path == "/api/jobs/retention":
            retention = self.services.LOG_RETENTION
            value = retention.snapshot() if retention is not None else {'initialized': False}
            writer = self.services.LOG_WRITER
            if writer is not None:
                value['outputWriter'] = writer.status()
            self.send_json(value)
            return
        if path == "/api/jobs":
            current_lang = self.services.active_language()
            with self.services.STATE_LOCK:
                jobs = sorted(self.services.JOBS.values(), key=lambda item: item.created_at, reverse=True)[:50]
            self.send_json({"jobs": [job.public(lang=current_lang) for job in jobs]})
            return
        if path.startswith("/api/jobs/"):
            job_id = path.rsplit("/", 1)[-1]
            job = self.services.JOBS.get(job_id)
            if not job:
                self.send_json({"error": "Nie znaleziono zadania."}, HTTPStatus.NOT_FOUND)
            else:
                self.send_json(job.public(include_output=True, lang=self.services.active_language()))
            return
        paged_routes = {
            "/api/data/corrections": self.services.get_dispatch_corrections,
            "/api/chat/messages": lambda params: {"messages": (page := self.services.api_page(f"{self.services.GATEWAY_API}/chat/messages", params)).pop("items"), **page},
            "/api/data/orders": self.services.get_orders_data,
            "/api/data/invoices": self.services.get_invoices_data,
            "/api/data/customers": self.services.get_customers_data,
            "/api/data/inventory": self.services.get_inventory_data,
        }
        chat_history = re.fullmatch(r"/api/chat/(\d+)/entries", path)
        if chat_history:
            try:
                query = parse_qs(parsed.query)
                before = int(query.get("before", ["0"])[0]); size = int(query.get("size", ["50"])[0])
                if before < 0 or not 1 <= size <= 100:
                    raise ValueError("Nieprawidłowa strona historii.")
                self.send_json(self.services.api_json(f"{self.services.GATEWAY_API}/chat/messages/{chat_history[1]}/entries?before={before}&size={size}"))
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            except Exception:
                self.send_json({"error": "Nie udało się odczytać historii rozmowy."}, HTTPStatus.BAD_GATEWAY)
            return
        balance_match = re.fullmatch(r"/api/data/invoices/(\d+)/correction-balance", path)
        if balance_match:
            try:
                self.send_json(self.services.api_json(f"{self.services.GATEWAY_API}/invoices/{balance_match[1]}/correction-balance"))
            except Exception:
                self.send_json({"error": "Nie udało się odczytać bieżącego salda faktury."}, HTTPStatus.BAD_GATEWAY)
            return
        correction_match = re.fullmatch(r"/api/data/invoices/(\d+)/corrections", path)
        if path in paged_routes or correction_match:
            try:
                params = self.services.list_params(parse_qs(parsed.query))
                result = self.services.get_corrections_data(int(correction_match[1]), params) if correction_match else paged_routes[path](params)
                self.send_json(result)
            except self.services.CustomerListError as error:
                self.send_json({"error": str(error)}, error.status)
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            except Exception as error:
                self.send_json({"error": f"Odczyt strony nie powiódł się: {error}"}, HTTPStatus.BAD_GATEWAY)
            return
        if path.startswith("/api/terminal/"):
            session_id = path.split("/")[3]
            session = self.services.TERMINALS.get(session_id)
            if not session:
                self.send_json({"error": "Sesja terminala nie istnieje."}, HTTPStatus.NOT_FOUND)
                return
            with session.lock:
                output = self.services.ANSI_RE.sub("", session.output.decode("utf-8", errors="replace"))
            self.send_json({"id": session.id, "alive": session.alive, "output": output[-150_000:]})
            return
        if path == "/api/data/invoices/export-zip":
            try:
                params = self.services.export_params(parse_qs(parsed.query))
                suffix = "?" + urllib.parse.urlencode(params) if params else ""
                req = self.services.platform_request(f"{self.services.GATEWAY_API}/invoices/export/zip{suffix}")
                resp = urllib.request.urlopen(req, timeout=30.0)
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
                return
            except urllib.error.HTTPError as error:
                try:
                    payload = json.loads(error.read(8192).decode("utf-8"))
                    message = payload.get("message") or payload.get("detail") or payload.get("error")
                except Exception:
                    message = None
                self.send_json({"error": message or f"Eksport odrzucony (HTTP {error.code})."}, error.code)
                return
            except Exception:
                self.send_json({"error": "Nie udało się pobrać archiwum ZIP z Billing Service."}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Disposition", 'attachment; filename="faktury.zip"')
            self.end_headers()
            try:
                while chunk := resp.read(65536):
                    self.wfile.write(chunk)
                    self.wfile.flush()
            finally:
                resp.close()
            return
        if path.startswith("/api/data/invoices/corrections/") and path.endswith("/pdf"):
            parts = path.strip("/").split("/")
            correction_id = parts[4]
            resp = None
            for pdf_url in [f"{self.services.GATEWAY_API}/invoices/corrections/{correction_id}/pdf", f"{self.services.BILLING_DIRECT_API}/invoices/corrections/{correction_id}/pdf"]:
                try:
                    req = self.services.platform_request(pdf_url)
                    resp = urllib.request.urlopen(req, timeout=10.0)
                    break
                except Exception:
                    continue
            if not resp:
                self.send_json({"error": "Nie udało się pobrać PDF korekty."}, HTTPStatus.BAD_GATEWAY)
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Disposition", f'attachment; filename="KOR_{correction_id}.pdf"')
            self.end_headers()
            try:
                while chunk := resp.read(65536):
                    self.wfile.write(chunk)
            finally:
                resp.close()
            return
        if path == "/api/data/invoices/dispatch-history":
            try:self.send_json({"history": self.services.load_dispatch_history(),"metadataOnly":True,"legacyReviewRequired":self.services.DISPATCH_HISTORY_FILE.exists()})
            except (OSError,RuntimeError,ValueError):self.send_json({'error':'Historia wysyłek wymaga oceny; nie ponawiaj wysyłki dokumentu.','code':'CC_HISTORY_UNAVAILABLE'},HTTPStatus.SERVICE_UNAVAILABLE)
            return
        if path == "/api/events/stream":
            query_params = parse_qs(parsed.query)
            st_name = query_params.get("stream", ["payment-events"])[0]
            self.send_json({"stream": st_name, "events": self.services.read_redis_stream(stream_name=st_name)})
            return
        if path == "/api/events/outbox":
            self.send_json({"outbox": self.services.get_outbox_events()})
            return
        if path == "/api/events/dlq":
            self.send_json({"dlq": self.services.get_dlq_events()})
            return
        if path == "/api/trace":
            query_params = parse_qs(parsed.query)
            cid = query_params.get("correlationId", [""])[0]
            self.send_json({"correlationId": cid, "timeline": self.services.trace_correlation_id(cid)})
            return
        if path == "/api/chaos/status":
            self.send_json({"targets": self.services.get_chaos_status()})
            return
        if path == "/api/system/diagnostic-report":
            report_lines = [
                f"# 📋 Mini Order Platform — Raport Diagnostyczny Środowiska",
                f"> Wygenerowano: {time.strftime('%Y-%m-%d %H:%M:%S UTC', time.gmtime())}",
                f"> Profil: **{self.services.active_profile()}** | Język: **{self.services.active_language()}**",
                "",
                "## 🌐 Stan Mikroserwisów i Frontendu",
            ]
            with ThreadPoolExecutor(max_workers=len(self.services.SERVICES)) as pool:
                srv_res = list(pool.map(self.services.check_service, self.services.SERVICES))
            online_count = sum(1 for s in srv_res if s["online"])
            report_lines.append(f"**Podsumowanie:** {online_count}/{len(self.services.SERVICES)} mikroserwisów ONLINE\n")
            report_lines.append("| Mikroserwis | Port | Status | Czas odpowiedzi |")
            report_lines.append("| :--- | :--- | :--- | :--- |")
            for s in srv_res:
                st_icon = "🟢 UP" if s["online"] else "🔴 OFFLINE"
                report_lines.append(f"| {s['name']} | `{s['port']}` | {st_icon} | {s['latencyMs']} ms |")

            report_lines.extend([
                "",
                "## 🔒 Stan Skarbca Sekretów SOPS / Age",
            ])
            sec_profiles = self.services.public_secret_profiles()
            for prof_name, prof_data in sec_profiles.items():
                k_stat = "OK" if prof_data.get("key") else "BRAK"
                f_stat = "OK" if prof_data.get("file") else "BRAK"
                report_lines.append(f"- **Profil `{prof_name}`**: Klucz Age: `{k_stat}`, Plik SOPS: `{f_stat}`")

            report_lines.extend([
                "",
                "## 🐳 Ostatnie Zadania i Logi",
            ])
            with self.services.STATE_LOCK:
                recent_jobs = sorted(self.services.JOBS.values(), key=lambda j: j.created_at, reverse=True)[:5]
            if recent_jobs:
                for j in recent_jobs:
                    report_lines.append(f"- **{j.title}** (`{j.action_id}`): Status `{j.status}` (kod: `{j.exit_code}`)")
            else:
                report_lines.append("- Brak zarejestrowanych zadań w bieżącej sesji.")

            self.send_json({
                "markdown": "\n".join(report_lines),
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
                "profile": self.services.active_profile(),
                "language": self.services.active_language(),
                "onlineCount": online_count,
                "totalCount": len(self.services.SERVICES),
                "services": srv_res,
                "secretProfiles": sec_profiles,
                "recentJobs": [
                    {
                        "id": j.id,
                        "title": j.public(lang=self.services.active_language())["title"],
                        "actionId": j.action_id,
                        "status": j.status,
                        "exitCode": j.exit_code,
                        "createdAt": j.created_at,
                    }
                    for j in (recent_jobs if recent_jobs else [])
                ],
            })
            return
        super().do_GET()

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == '/api/session/logout':
            try:self.server.panel_sessions.revoke(self.headers.get('X-Control-Center-Token'),self.panel_authority)
            except self.services.PanelRejected as error:
                self.send_json({'error':error.message,'code':error.code},error.status);return
            self.server.ai_reviews.clear(self.panel_session_key)
            self.send_json({'ok':True,'sessionRevoked':True});return
        try:
            payload = self.read_json()
        except (ValueError, json.JSONDecodeError) as error:
            self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/language":
            lang = str(payload.get("language", "pl")).strip().lower()
            lang = "en" if lang in {"en", "english"} else "pl"
            self.services.LANGUAGE_FILE.parent.mkdir(parents=True, exist_ok=True)
            self.services.LANGUAGE_FILE.write_text(f"{lang}\n", encoding="utf-8")
            self.send_json({"ok": True, "language": lang, "actions": self.services.build_actions(lang)})
            return
        if path == "/api/secrets/setup":
            if payload.get("confirmed") is not True:
                self.send_json({"error": "Potwierdź utworzenie zaszyfrowanego pliku sekretów."}, HTTPStatus.CONFLICT)
                return
            try:
                profile = str(payload.get("profile", ""))
                created = self.services.setup_profile_secrets(profile, payload.get("values") or {})
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
                updated = self.services.update_profile_secrets(str(payload.get("profile", "")), payload.get("values") or {})
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
            selected = self.services.ACTION_BY_ID.get(action_id)
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
            command = ["bash", str(self.services.CONTROL_SCRIPT), selected["command"], *args]
            job = self.services.Job(job_id, action_id, selected["title"], command, self.services.JOB_ROOT / f"{job_id}.log")
            try:
                self.services.register_job(job)
            except self.services.ResourceLimitError as error:
                self.send_json({'error': str(error), 'code': 'CC_RESOURCE_LIMIT'}, HTTPStatus.TOO_MANY_REQUESTS)
                return
            except self.services.cc_job_logs.LogSafetyError:
                self.send_json({'error': 'Nie można bezpiecznie przygotować logu zadania.'}, HTTPStatus.SERVICE_UNAVAILABLE)
                return
            threading.Thread(target=self.services.run_job, args=(job, stdin_text), daemon=True).start()
            self.send_json(job.public(), HTTPStatus.ACCEPTED)
            return
        if path.startswith("/api/chat/") and path.endswith("/reply"):
            parts = path.strip("/").split("/")
            try:
                message_id = int(parts[2])
                message = str(payload.get("message", "")).strip()
                if not message:
                    raise ValueError("Odpowiedź nie może być pusta.")
                request_id = str(payload.get("requestId") or uuid.uuid4())
                if not re.fullmatch(r"[A-Za-z0-9._:-]{1,100}", request_id):
                    raise ValueError("Nieprawidłowy klucz wiadomości.")
                result = self.services.api_json(f"{self.services.GATEWAY_API}/chat/messages/{message_id}/reply", method="POST", payload={"message": message, "requestId": request_id})
                self.send_json({"message": result})
            except ValueError as error:
                self.send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
            except Exception as error:
                self.send_json({"error": f"Nie udało się wysłać odpowiedzi: {error}"}, HTTPStatus.BAD_GATEWAY)
            return
        if path == "/api/terminal":
            try:
                session = self.services.start_terminal()
                self.send_json({"id": session.id, "alive": True}, HTTPStatus.CREATED)
            except self.services.ResourceLimitError as error:
                self.send_json({'error': str(error), 'code': 'CC_RESOURCE_LIMIT'}, HTTPStatus.TOO_MANY_REQUESTS)
            except Exception as error:
                self.send_json({"error": f"Nie udało się uruchomić terminala: {error}"}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if path.startswith("/api/terminal/") and path.endswith("/input"):
            session_id = path.split("/")[3]
            session = self.services.TERMINALS.get(session_id)
            if not session or not session.alive:
                self.send_json({"error": "Sesja terminala jest zamknięta."}, HTTPStatus.GONE)
                return
            data = str(payload.get("data", ""))
            session.last_activity = time.monotonic()
            os.write(session.fd, data.encode("utf-8"))
            self.send_json({"ok": True})
            return
        if path == "/api/ai/config":
            try:
                result = self.services.save_ai_config(payload)
                self.server.ai_reviews.clear()
                self.send_json(result)
            except self.services.AiSettingsError as error: self.send_json({'error': str(error), 'code': 'CC_AI_CONFIG_REJECTED'}, HTTPStatus.BAD_REQUEST)
            return
        if path in {"/api/ai/chat", "/api/ai/chat/preview"}:
            msg, history = payload.get('message', ''), payload.get('history', [])
            lang = self.services.active_language()
            try:
                plan = self.services.prepare_ai_delivery(msg, history, lang)
                if path.endswith('/preview'):
                    result = plan.public_view()
                    if plan.external: result.update(self.server.ai_reviews.issue(self.panel_session_key, plan))
                else:
                    approval = self.server.ai_reviews.consume(self.panel_session_key, payload.get('reviewToken'), plan) if plan.external else None
                    result = self.services.handle_ai_chat(msg, history, lang, approval, plan)
            except self.services.AiDeliveryError as error:
                self.send_json({'error': str(error), 'code': error.code}, error.status)
                return
            except self.services.AiSettingsError as error:
                self.send_json({'error': str(error), 'code': 'CC_AI_STORAGE_UNAVAILABLE'}, HTTPStatus.SERVICE_UNAVAILABLE)
                return
            self.send_json(result)
            return
        if path == "/api/data/inventory/restock":
            try:
                res = self.services.restock_inventory(payload.get("productId"), payload.get("quantity"), payload.get("operationId"))
                self.send_json(res)
            except ValueError as err:
                self.send_json({"error": str(err)}, HTTPStatus.BAD_REQUEST)
            except self.services.StockOperationError as err:
                self.send_json({"error": str(err), "operationId": err.operation_id, "code": err.code}, err.status)
            return
        if path == "/api/events/dlq/replay":
            dlq_id = payload.get("id")
            if not dlq_id:
                self.send_json({"error": "Wymagane ID wpisu DLQ."}, HTTPStatus.BAD_REQUEST)
                return
            try:
                res = self.services.replay_dlq_entry(dlq_id)
                self.send_json(res)
            except Exception as err:
                self.send_json({"error": str(err)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return
        if path == "/api/chaos/toggle":
            target = str(payload.get("target", "")).strip()
            action = str(payload.get("action", "")).strip()
            try:
                res = self.services.apply_chaos_action(target, action)
                self.send_json(res)
            except Exception as err:
                self.send_json({"error": str(err)}, HTTPStatus.INTERNAL_SERVER_ERROR)
        if path == "/api/data/orders/generate-invoice":
            order_id = payload.get("orderId")
            if not order_id:
                self.send_json({"error": "Brak parametru orderId."}, HTTPStatus.BAD_REQUEST)
                return
            result = self.services.generate_invoice_for_order(int(order_id))
            if result.get("success"):
                self.send_json(result)
            else:
                self.send_json(result, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/data/invoices/correct":
            try:
                self.send_json(self.services.issue_correction(self.services.api_json,[self.services.GATEWAY_API,self.services.BILLING_DIRECT_API],payload.get("invoiceId"),
                    payload.get("reason"),payload.get("correctionGross"),payload.get("idempotencyKey"),payload.get("expectedVersion")))
            except ValueError as error:
                self.send_json({"error":str(error),"definitive":True},HTTPStatus.BAD_REQUEST)
            except self.services.CorrectionError as error:
                self.send_json({"error":str(error),"definitive":error.definitive},error.status)
            return
        if path == "/api/data/invoices/send-email":
            doc_type = payload.get("documentType", "INVOICE")
            doc_id = payload.get("documentId")
            order_id = payload.get("orderId")
            doc_number = str(payload.get("documentNumber", "")).strip()
            recipient = str(payload.get("recipient", "")).strip()
            subject = str(payload.get("subject", "")).strip()
            custom_message = str(payload.get("customMessage", "")).strip()
            buyer_name = str(payload.get("buyerName", "")).strip()

            if not doc_id or not recipient or not doc_number:
                self.send_json({"error": "Wymagane pola: documentId, documentNumber, recipient."}, HTTPStatus.BAD_REQUEST)
                return

            res = self.services.send_document_email(
                doc_type=doc_type,
                doc_id=doc_id,
                order_id=order_id,
                doc_number=doc_number,
                recipient=recipient,
                subject=subject,
                custom_message=custom_message,
                buyer_name=buyer_name,
            )
            if res.get("success"):
                self.send_json(res)
            else:
                self.send_json(res, HTTPStatus.BAD_REQUEST)
            return
        if path == "/api/system/reload":
            self.send_json({"ok": True, "message": "Przeładowanie panelu zatrzymuje jego aktywne zadania i terminale."})
            threading.Thread(target=self.services.reload_server, daemon=True).start()
            return
        self.send_json({"error": "Nieznany endpoint."}, HTTPStatus.NOT_FOUND)

"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
import cc_ai_delivery
from typing import Any
from concurrent.futures import ThreadPoolExecutor
from datetime import date
import json
import subprocess
import urllib.request

def load_ai_config(services) -> dict[str, Any]:
    return services.AI_SETTINGS.load()

def save_ai_config(services, cfg: dict[str, Any]) -> dict[str, Any]:
    return services.public_config(services.AI_SETTINGS.save(cfg))

def public_ai_config(services) -> dict[str, Any]:
    return services.public_config(services.load_ai_config())

def get_project_system_prompt(services, lang: str = "pl") -> str:
    if lang == "en":
        return """You are the MiniTech AI Architecture Assistant for Mini Order Platform.
Project details:
- Monorepo with 15 Spring Boot 3.4 microservices (Java 25, Maven) + vanilla JS Storefront frontend (:18070).
- Microservices & Ports:
  * API Gateway: 18080 (routing to /api/products, /api/orders, /api/inventory, /api/payments, /api/customers, etc.)
  * Product Service: 18081 (PostgreSQL product_db)
  * Order Service: 18082 (PostgreSQL order_db, Outbox pattern, Redis Streams events)
  * Inventory Service: 18083 (PostgreSQL inventory_db)
  * Payment Service: 18084 (PostgreSQL payment_db)
  * Notification Service: 18086 (PostgreSQL notification_db, Mailpit :1025, Web mailbox :18025)
  * Customer Service: 18087 (PostgreSQL customer_db, JWT auth)
  * Delivery Service: 18088 (PostgreSQL delivery_db)
  * Promotion Service: 18089 (PostgreSQL promotion_db)
  * Wishlist Service: 18090 (PostgreSQL wishlist_db)
  * Review Service: 18091 (PostgreSQL review_db)
  * Recommendation Service: 18092 (PostgreSQL recommendation_db)
  * Chat Service: 18093 (PostgreSQL chat_db)
  * Returns Service: 18094 (PostgreSQL returns_db)
  * Billing Service: 18095 (PostgreSQL billing_db)
- Infrastructure:
  * 14 isolated PostgreSQL databases on ports 15432-15446.
  * Redis Streams event broker (localhost:16379, container event-broker:6379)
  * Mailpit SMTP :18025 / :1025
  * Prometheus :19090, Grafana :13000
  * MiniTech Control Center :18100
- Docker stacks: compose.yaml (Databases + Redis + Mailpit), compose.apps.yaml (All 15 microservices + frontend), compose.monitoring.yaml.
- Security & Secrets: age keys + Mozilla SOPS (.env.sops).
- Scripts: bash ./scripts/start.sh, docker-apps-up, docker-apps-down, infra-up, infra-down, app-verify.
Provide clear, structured, technical markdown answers with code snippets and commands when relevant."""
    else:
        return """Jesteś Asystentem Architektury AI dla projektu Mini Order Platform (MiniTech).
Informacje o projekcie:
- Monorepo z 15 mikroserwisami Spring Boot 3.4 (Java 25, Maven) + frontend sklepu w czystym JS (:18070).
- Mikroserwisy i ich porty lokalne:
  * API Gateway: :18080 (routing do /api/products, /api/orders, /api/inventory, /api/payments, /api/customers, itd.)
  * Product Service: :18081 (PostgreSQL product_db)
  * Order Service: :18082 (PostgreSQL order_db, wzorzec Outbox, publikacja zdarzeń do Redis Streams)
  * Inventory Service: :18083 (PostgreSQL inventory_db)
  * Payment Service: :18084 (PostgreSQL payment_db)
  * Notification Service: :18086 (PostgreSQL notification_db, Mailpit SMTP :1025, interfejs web :18025)
  * Customer Service: :18087 (PostgreSQL customer_db, autoryzacja RSA / JWT)
  * Delivery Service: :18088 (PostgreSQL delivery_db)
  * Promotion Service: :18089 (PostgreSQL promotion_db)
  * Wishlist Service: :18090 (PostgreSQL wishlist_db)
  * Review Service: :18091 (PostgreSQL review_db)
  * Recommendation Service: :18092 (PostgreSQL recommendation_db)
  * Chat Service: :18093 (PostgreSQL chat_db, obsługa czatu klientów)
  * Returns Service: :18094 (PostgreSQL returns_db, zwroty towarów)
  * Billing Service: :18095 (PostgreSQL billing_db, faktury i rozliczenia)
- Infrastruktura:
  * 14 odizolowanych baz PostgreSQL na portach 15432-15446.
  * Redis Streams event broker (lokalnie 16379, w sieci Docker container: event-broker:6379)
  * Mailpit testowy serwer poczty :18025 (web) / :1025 (smtp)
  * Prometheus :19090, Grafana :13000
  * Centrala MiniTech Control :18100
- Stosy Docker Compose:
  * `compose.yaml` – bazy danych, Redis broker, Mailpit.
  * `compose.apps.yaml` – wszystkie 15 mikroserwisów i frontend.
  * `compose.monitoring.yaml` – Prometheus i Grafana.
- Bezpieczeństwo i sekrety: Klucze age + Mozilla SOPS (`.env.sops`).
- Główne polecenia Centrali: `bash ./scripts/start.sh`, `docker-apps-up`, `docker-apps-down`, `infra-up`, `infra-down`, `app-verify`, `jwt-status`.
Odpowiadaj konkretnie, w formacie markdown, z tabelami, listami i przykładami poleceń, gdy to przydatne."""

def query_customers_db(services) -> list[dict[str, Any]]:
    cmd = [
        "docker", "exec", "-i", "miniorderplatform-customer-db-1",
        "psql", "-U", "customer_app", "-d", "customer_db",
        "-t", "-A", "-F", "\t",
        "-c", "SELECT id, email, first_name, last_name, city, enabled, created_at FROM customers ORDER BY id DESC LIMIT 100;"
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        if proc.returncode == 0 and proc.stdout.strip():
            rows = []
            for line in proc.stdout.strip().splitlines():
                parts = line.split("\t")
                if len(parts) >= 7:
                    rows.append({
                        "id": parts[0],
                        "email": parts[1],
                        "firstName": parts[2],
                        "lastName": parts[3],
                        "city": parts[4],
                        "enabled": parts[5] in {"t", "true", "1"},
                        "createdAt": parts[6]
                    })
            return rows
    except Exception:
        pass
    return []

def query_reviews_db(services) -> list[dict[str, Any]]:
    cmd = [
        "docker", "exec", "-i", "miniorderplatform-review-db-1",
        "psql", "-U", "review_app", "-d", "review_db",
        "-t", "-A", "-F", "\t",
        "-c", "SELECT id, product_id, rating, title, content, status, created_at FROM reviews ORDER BY id DESC LIMIT 100;"
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        if proc.returncode == 0 and proc.stdout.strip():
            rows = []
            for line in proc.stdout.strip().splitlines():
                parts = line.split("\t")
                if len(parts) >= 6:
                    rows.append({
                        "id": parts[0],
                        "productId": parts[1],
                        "rating": int(parts[2]) if parts[2].isdigit() else 5,
                        "title": parts[3],
                        "content": parts[4],
                        "status": parts[5],
                        "createdAt": parts[6] if len(parts) > 6 else ""
                    })
            return rows
    except Exception:
        pass
    return []

def generate_local_knowledge_reply(services, query: str, lang: str = "pl") -> str:
    q = query.lower().strip()
    is_en = lang == "en"

    # 1. Customer Support Chat inquiries
    if any(k in q for k in ["czat", "chat", "wiadomośc", "wiadomos", "inquiry", "ticket", "pomoc"]):
        try:
            chat_data = services.api_json(f"{services.GATEWAY_API}/chat/messages", timeout=3)
            if isinstance(chat_data, list):
                count = len(chat_data)
                lines = [
                    f"### 💬 Zgłoszenia i czat klientów ({count} wątków)" if not is_en else f"### 💬 Customer Chat Inquiries ({count} threads)",
                    "",
                    "| ID | Klient | Temat | Status | Data |" if not is_en else "| ID | Customer | Subject | Status | Date |",
                    "| :---: | :--- | :--- | :---: | :--- |"
                ]
                for c in chat_data[:6]:
                    c_date = str(c.get("createdAt", ""))[:16].replace("T", " ")
                    lines.append(f"| `#{c.get('id')}` | {c.get('customerName')} (`{c.get('customerEmail')}`) | **{c.get('subject')}** | `{c.get('status')}` | {c_date} |")
                lines.extend([
                    "",
                    "💡 **Wskazówka:** Możesz odpowiadać klientom bezpośrednio w zakładce **Czat klientów** w lewym menu Centrali."
                ])
                return "\n".join(lines)
        except Exception:
            pass

    # 2. Customer Reviews
    if any(k in q for k in ["opini", "recenzj", "ocen", "review"]):
        reviews = services.query_reviews_db()
        if reviews:
            count = len(reviews)
            lines = [
                f"### ⭐ Ostatnie opinie klientów (próbka do 100: {count})" if not is_en else f"### ⭐ Recent customer reviews (sample up to 100: {count})",
                "",
                "Oto opinie i recenzje produktów zapisane w bazie `review_db`:" if not is_en else "Customer reviews registered in `review_db`:",
                "",
                "| ID | Produkt | Ocena | Tytuł | Treść opinii | Status |" if not is_en else "| ID | Product | Rating | Title | Review Content | Status |",
                "| :---: | :---: | :---: | :--- | :--- | :---: |"
            ]
            for r in reviews:
                stars = "★" * r.get("rating", 5)
                st_badge = "🟢 " + r["status"] if r["status"] == "PUBLISHED" else ("🔴 " + r["status"] if r["status"] == "REJECTED" else "🟡 " + r["status"])
                lines.append(f"| `#{r['id']}` | `Produkt #{r['productId']}` | **{stars}** | **{r['title']}** | {r['content']} | `{st_badge}` |")
            if not is_en:
                lines.extend([
                    "",
                    "💡 **Wskazówka:** W panelu sklepu klienci mogą dodawać opinie do zakupionych produktów po zrealizowaniu zamówienia."
                ])
            return "\n".join(lines)
        else:
            if is_en:
                return "### ⭐ Customer Reviews\n\nThere are currently no reviews in `review_db`."
            else:
                return "### ⭐ Opinie klientów o produktach\n\nAktualnie w bazie `review_db` nie ma jeszcze żadnych opinii klientów.\n\nMożesz wygenerować przykładowe recenzje kafelkiem **`📦 Zasil danymi demo`**."

    # 3. Promotion codes & Discounts
    if any(k in q for k in ["promocj", "kod", "rabat", "kupon", "promo", "coupon", "discount"]):
        try:
            codes = services.api_json(f"{services.GATEWAY_API}/promotions/codes", timeout=3)
            if isinstance(codes, list):
                if not codes:
                    return (
                        "### 🏷️ Kody rabatowe\n\nAktualnie w bazie `promotion_db` nie ma jeszcze żadnych kodów rabatowych.\n\n"
                        "- Możesz utworzyć nowy kod kafelkiem **`Utwórz kod rabatowy`** na Pulpicie Centrali.\n"
                        "- Lub zasilić sklep przykładowymi kodami (np. `MINITECH10`, `VIP20`) kafelkiem **`📦 Zasil danymi demo`**."
                        if not is_en else
                        "### 🏷️ Promotion Codes\n\nThere are currently no promotion codes in `promotion_db`.\n\n"
                        "- You can create a new code using the **`Create promotion code`** action.\n"
                        "- Or populate demo codes using the **`📦 Seed Demo Data`** tile in Centrala."
                    )
                count = len(codes)
                today = date.today()
                lines = [
                    f"### 🏷️ Kody promocyjne w sklepie ({count} kodów)" if not is_en else f"### 🏷️ Promotion Codes ({count} codes)",
                    "",
                    "Oto lista kodów rabatowych zarejestrowanych w `Promotion Service` wraz ze statusem ich ważności:" if not is_en else "Here is the list of promotion codes registered in `Promotion Service` with validity status:",
                    "",
                    "| Kod rabatowy | Wysokość rabatu | Opis promocji | Status ważności | Liczba użyć |" if not is_en else "| Promo Code | Discount | Description | Validity Status | Redemptions |",
                    "| :--- | :---: | :--- | :---: | :---: |"
                ]
                for cd in codes:
                    v_to_str = cd.get("validTo")
                    v_from_str = cd.get("validFrom")
                    v_to = date.fromisoformat(v_to_str) if v_to_str else None
                    v_from = date.fromisoformat(v_from_str) if v_from_str else None
                    max_r = cd.get("maxRedemptions")
                    red_c = cd.get("redeemedCount", 0)

                    if not cd.get("active"):
                        st = "🔴 Nieaktywny" if not is_en else "🔴 Inactive"
                    elif v_from and today < v_from:
                        st = f"🟡 Ważny od {v_from}" if not is_en else f"🟡 Valid from {v_from}"
                    elif v_to and today > v_to:
                        st = f"🔴 Wygasł ({v_to})" if not is_en else f"🔴 Expired ({v_to})"
                    elif max_r is not None and red_c >= max_r:
                        st = f"🔴 Limit wyczerpany ({red_c}/{max_r})" if not is_en else f"🔴 Limit reached ({red_c}/{max_r})"
                    else:
                        st = f"🟢 Ważny do {v_to or 'bezterminowo'}" if not is_en else f"🟢 Valid until {v_to or 'indefinite'}"

                    lim_label = f"{red_c}/{max_r if max_r is not None else '∞'}"
                    lines.append(f"| **`{cd.get('code')}`** | **-{cd.get('discountPercent')}%** | {cd.get('description')} | {st} | {lim_label} |")
                if not is_en:
                    lines.extend([
                        "",
                        "💡 **Wskazówka:** Możesz użyć aktywnych kodów w koszyku w sklepie (`:18070`) lub wygenerować nowe kody kafelkiem **`Utwórz kod rabatowy`** na Pulpicie."
                    ])
                return "\n".join(lines)
        except Exception as err:
            if not is_en:
                return f"### 🏷️ Kody rabatowe\n\nNie udało się pobrać kodów z Promotion Service: `{err}`.\n\nUpewnij się, że usługa działa (port `:18089`)."
            return f"### 🏷️ Promotion Codes\n\nFailed to fetch codes from Promotion Service: `{err}`.\n\nMake sure the service is running (port `:18089`)."

    # 4. Registered Customer Accounts
    if any(k in q for k in ["konta", "konto", "klient", "user", "użytkownik", "uzytkownik", "rejestracj", "account", "customer"]):
        customers = services.query_customers_db()
        if customers:
            count = len(customers)
            lines = [
                f"### 👥 Ostatnie konta klientów (próbka do 100: {count})" if not is_en else f"### 👥 Recent customer accounts (sample up to 100: {count})",
                "",
                "W bazie danych `customer_db` znajdują się następujące konta klientów:" if not is_en else "The following accounts are registered in `customer_db`:",
                "",
                "| ID | Imię i Nazwisko | E-mail | Miasto | Status | Data rejestracji |" if not is_en else "| ID | Name | Email | City | Status | Registered At |",
                "| :---: | :--- | :--- | :--- | :---: | :--- |"
            ]
            for c in customers:
                st = "🟢 Aktywne" if c["enabled"] else "🟡 Weryfikacja"
                if is_en:
                    st = "🟢 Active" if c["enabled"] else "🟡 Pending"
                full_name = f"{c['firstName']} {c['lastName']}".strip()
                date_str = str(c.get("createdAt", ""))[:16].replace("T", " ")
                lines.append(f"| `#{c['id']}` | **{full_name}** | `{c['email']}` | {c['city']} | {st} | {date_str} |")
            if not is_en:
                lines.extend([
                    "",
                    "💡 **Wskazówka:** Możesz sprawdzić dziennik audytu logowań w **Operacje → Dziennik audytowy (`audit-list`)**."
                ])
            return "\n".join(lines)

    # 5. Product Catalog
    if any(k in q for k in ["produkt", "product", "katalog", "towar", "magazyn", "stock"]):
        try:
            products = services.api_json(f"{services.GATEWAY_API}/products", timeout=3)
            if isinstance(products, list) and products:
                count = len(products)
                lines = [
                    f"### 🛒 Katalog produktów w sklepie ({count} pozycji)" if not is_en else f"### 🛒 Product Catalog ({count} products)",
                    "",
                    "| ID | Nazwa produktu | Cena | Kategoria | Producent |" if not is_en else "| ID | Product Name | Price | Category | Manufacturer |",
                    "| :---: | :--- | :---: | :--- | :--- |"
                ]
                for p in products[:8]:
                    lines.append(f"| `#{p.get('id')}` | **{p.get('name')}** | {p.get('price')} PLN | {p.get('category', '—')} | {p.get('manufacturer', '—')} |")
                lines.extend([
                    "",
                    f"*(Wyświetlono 8 z {count} produktów. Pełny katalog dostępny w sklepie: http://127.0.0.1:18070)*"
                ])
                return "\n".join(lines)
        except Exception as err:
            if not is_en:
                return f"### 🛒 Katalog produktów\n\nNie udało się połączyć z Product Service: `{err}`."

    # 6. Invoices & Billing Service
    if any(k in q for k in ["faktur", "billing", "invoice", "vat", "rachun"]):
        if is_en:
            return """### 🧾 Invoices & Billing Service (:18095):

1. **How it works:**
   - When an order is paid (`OrderPaid` event via Redis Streams), **Billing Service** automatically generates an invoice with a sequential VAT number and PDF document.
2. **Accessing invoices:**
   - In Centrala: Go to **Operations → Show invoice (`invoice-show`)** or **Download invoice PDF (`invoice-pdf`)**.
   - In Storefront: Customers can view and download invoices directly from their order history."""
        else:
            return """### 🧾 Faktury i Billing Service (:18095):

1. **Zasada działania:**
   - Po opłaceniu zamówienia zdarzenie `OrderPaid` trafia przez Redis Streams do **Billing Service**.
   - Usługa automatycznie rejestruje fakturę z kolejnym numerem VAT oraz generuje plik PDF.
2. **Przeglądanie i pobieranie:**
   - W Centrali: zakładka **Operacje → Pokaż fakturę (`invoice-show`)** lub **Pobierz PDF faktury (`invoice-pdf`)**.
   - W sklepie: klient może pobrać fakturę w szczegółach swojego zamówienia."""

    # 7. Deliveries & Shipping
    if any(k in q for k in ["dostaw", "kurier", "paczkomat", "delivery", "przesyłk", "przesylk", "tracking", "wysyłk"]):
        if is_en:
            return """### 🚚 Deliveries & Delivery Service (:18088):

1. **Supported Delivery Methods:**
   - `STANDARD_COURIER` – standard door-to-door courier delivery.
   - `PARCEL_LOCKER` – delivery to automated parcel locker machines.
   - `STORE_PICKUP` – free customer pickup at the physical store.
2. **Package Tracking:**
   - Check status and carrier in Centrala: **Operations → Delivery tracking (`delivery-tracking`)**.
   - Update delivery status in dev/test: **Operations → Update delivery status (`delivery-tracking-status`)**."""
        else:
            return """### 🚚 Dostawy i Delivery Service (:18088):

1. **Obsługiwane metody dostawy:**
   - `STANDARD_COURIER` – dostawa kurierem pod adres domowy / firmowy.
   - `PARCEL_LOCKER` – dostawa do Paczkomatu / automatu paczkowego.
   - `STORE_PICKUP` – bezpłatny odbiór osobisty w salonie stacjonarnym MiniTech.
2. **Śledzenie przesyłek:**
   - W Centrali: **Operacje → Śledzenie dostawy (`delivery-tracking`)**.
   - W dev/test możesz zmieniać status paczki (np. `IN_TRANSIT`, `DELIVERED`): **Operacje → Zmień status dostawy (`delivery-tracking-status`)**."""

    # 8. Real-time Live Orders query
    if any(k in q for k in ["zamówien", "zamowien", "order", "zakup"]):
        try:
            orders = services.api_json(f"{services.GATEWAY_API}/orders", timeout=3)
            if isinstance(orders, list):
                count = len(orders)
                if count == 0:
                    if is_en:
                        return "### 📦 Orders in Database\n\nThere are currently **no orders** in the database.\n\n- You can place an order via Storefront at http://127.0.0.1:18070\n- Or populate demo orders using the **`📦 Seed Demo Data`** tile in Centrala."
                    else:
                        return "### 📦 Zamówienia w bazie danych\n\nAktualnie w bazie **nie ma jeszcze żadnych zamówień** (baza jest pusta).\n\n- Możesz złożyć pierwsze zamówienie w sklepie: http://127.0.0.1:18070\n- Lub kliknąć kafelek **`📦 Zasil danymi demo`** na Pulpicie Centrali, aby wygenerować przykładowe zamówienia i faktury."
                else:
                    lines = [
                        f"### 📦 Zamówienia w bazie danych (Łącznie: {count})" if not is_en else f"### 📦 Orders in Database ({count} orders)",
                        "",
                        "Tak, w bazie danych znajdują się zamówienia. Oto lista ostatnich wpisów:" if not is_en else "Yes, there are orders in the database. Here is the recent list:",
                        "",
                        "| ID | Status | Kwota | E-mail klienta | Data | Pozycje |" if not is_en else "| ID | Status | Total | Customer Email | Date | Items |",
                        "| :---: | :---: | :---: | :--- | :--- | :---: |"
                    ]
                    for ord_item in orders[-8:]:
                        ord_id = ord_item.get("id", "—")
                        st = ord_item.get("status", "—")
                        total = f"{ord_item.get('totalPrice', 0):.2f} PLN"
                        email = ord_item.get("customerEmail", "—")
                        order_date = str(ord_item.get("createdAt", ""))[:16].replace("T", " ")
                        items_count = len(ord_item.get("items") or [])
                        lines.append(f"| `#{ord_id}` | `{st}` | **{total}** | {email} | {order_date} | {items_count} szt. |")
                    
                    if not is_en:
                        lines.extend([
                            "",
                            "💡 **Wskazówka:** Możesz pobrać PDF faktury wybranego zamówienia w **Operacje → Pobierz PDF faktury (`invoice-pdf`)**."
                        ])
                    return "\n".join(lines)
        except Exception as err:
            if not is_en:
                return f"### 📦 Zamówienia (Order Service)\n\nNie udało się połączyć z Order Service na porcie `:18082` przez API Gateway: `{err}`.\n\nUpewnij się, że serwisy są uruchomione (przycisk **`▶ Uruchom serwisy`** w Centrali)."

    # 9. Real-time Service Health
    if any(k in q for k in ["stan serwis", "stan usług", "stan uslug", "status serwis", "zdrowi", "działają", "dzialaja", "health", "offline", "online", "czy serwisy"]):
        with ThreadPoolExecutor(max_workers=len(services.SERVICES)) as pool:
            srv_res = list(pool.map(services.check_service, services.SERVICES))
        online_count = sum(1 for s in srv_res if s["online"])
        lines = [
            f"### 📊 Stan zdrowia mikroserwisów ({online_count}/{len(services.SERVICES)} ONLINE)" if not is_en else f"### 📊 Microservices Health ({online_count}/{len(services.SERVICES)} ONLINE)",
            "",
            "| Mikroserwis | Port | Status | Czas odpowiedzi |" if not is_en else "| Service | Port | Status | Latency |",
            "| :--- | :---: | :---: | :---: |"
        ]
        for s in srv_res:
            icon = "🟢 UP" if s["online"] else "🔴 OFFLINE"
            lines.append(f"| **{s['name']}** | `:{s['port']}` | {icon} | {s['latencyMs']} ms |")
        return "\n".join(lines)

    # 10. Troubleshooting & Error Knowledge Base
    if any(k in q for k in ["błąd", "blad", "błędy", "bledy", "problem", "awari", "troubleshoot", "napraw"]):
        if is_en:
            return """### 🚨 Error Knowledge Base & 1-Click Auto-Fix:

MiniTech platform includes a local Error Knowledge Base cataloging the top 12 common issues:

1. **PostgreSQL Connection Refused:** Database container down → click **`◫ Databases (infra-up)`**.
2. **JWT Secret Missing / Mismatch:** Run **`jwt-rotate dev`** or **`secrets-ensure-auth`**.
3. **Redis Event Broker Offline:** Run **`broker-up`**.
4. **Port Conflicts (18080/18100 in use):** Run **`app-stop-all`** or stop conflicting processes.
5. **Flyway Checksum Mismatch:** Review migration scripts in `db/migration`.

💡 *Full catalog: see `docs/ERROR_KNOWLEDGE_BASE.md` or click **🚑 Auto-Healing Doctor** on the Dashboard.*"""
        else:
            return """### 🚨 Baza Wiedzy o Błędach i Autodiagnostyka:

Platforma MiniTech posiada wbudowaną bazę 12 najczęstszych problemów i automatycznych rozwiązań:

1. **Brak połączenia z PostgreSQL (port 5432 refused):** Baza wyłączona → kliknij **`◫ Infrastruktura (infra-up)`**.
2. **Brak klucza JWT / MFA:** Wygeneruj klucze poleceniem **`jwt-rotate dev`** lub uzupełnij sekrety DEV.
3. **Broker Redis niedostępny:** Uruchom brokera przyciskiem **`Uruchom Redis`** w Operacjach.
4. **Zajęty port TCP (Address already in use):** Zatrzymaj blokujące procesy poleceniem **`app-stop-all`**.
5. **Błąd Flyway (suma kontrolna migracji):** Sprawdź pliki SQL w katalogu `db/migration`.

💡 *Pełny katalog znajdziesz w pliku `docs/ERROR_KNOWLEDGE_BASE.md` lub klikając **🚑 Skanuj i napraw** na Pulpicie Centrali.*"""

    # 11. Returns and RMA
    if any(k in q for k in ["zwrot", "reklamacj", "return", "rma"]):
        if is_en:
            return """### 🔄 Returns & Returns Service (:18094):

1. **Customer Complaints & Returns:**
   - Handles customer return requests, RMA tracking, and refund eligibility.
2. **Operations in Centrala:**
   - **`returns-list`** – View all customer return and complaint tickets.
   - **`returns-show`**, **`returns-history`** – Read the case version, items and decision journal.
   - **`returns-status <id> <status> <version> <UUID> <note>`** – Follow review/approval/rejection; keep the same UUID on retries. Approval requires separate financial/stock reconciliation; COMPLETED is blocked.
   - **`returns-reconcile`** – Verify an older nonterminal case against Order."""
        else:
            return """### 🔄 Zwroty i Returns Service (:18094):

1. **Obsługa zwrotów i reklamacji:**
   - Klienci mogą składać wnioski o zwrot lub reklamację zakupionych produktów.
2. **Zarządzanie w Centrali:**
   - **`returns-list`** (w Operacjach) – podgląd wszystkich zgłoszeń klientów.
   - **`returns-show`**, **`returns-history`** – wersja, pozycje i dziennik decyzji.
   - **`returns-status <id> <status> <wersja> <UUID> <notatka>`** – weryfikacja, zatwierdzenie lub odrzucenie; ponowienie zachowuje UUID. APPROVED wymaga osobnego rozliczenia pieniędzy/zapasu, a COMPLETED jest zablokowane.
   - **`returns-reconcile`** – weryfikacja starszego nieterminalnego zgłoszenia z Order."""

    # 11. Ports and Routing
    if any(k in q for k in ["port", "porty", "ports", "endpoint", "adres", "url"]):
        if is_en:
            return """### 🌐 MiniTech Microservices & Infrastructure Ports:

| Component | Port | Description / Database |
| :--- | :---: | :--- |
| **Frontend Storefront** | `:18070` | Customer shop UI |
| **API Gateway** | `:18080` | Spring Cloud Gateway entry point |
| **Product Service** | `:18081` | Product catalog (`product_db`) |
| **Order Service** | `:18082` | Order management & Outbox (`order_db`) |
| **Inventory Service** | `:18083` | Stock & inventory lock (`inventory_db`) |
| **Payment Service** | `:18084` | Payment processing (`payment_db`) |
| **Notification Service** | `:18086` | Email dispatcher (`notification_db`) |
| **Customer Service** | `:18087` | User profile & JWT Auth (`customer_db`) |
| **Delivery Service** | `:18088` | Shipping tracking (`delivery_db`) |
| **Promotion Service** | `:18089` | Discount coupons (`promotion_db`) |
| **Wishlist Service** | `:18090` | User wishlists (`wishlist_db`) |
| **Review Service** | `:18091` | Customer product reviews (`review_db`) |
| **Recommendation Service** | `:18092` | Product recommendations (`recommendation_db`) |
| **Chat Service** | `:18093` | Live customer support (`chat_db`) |
| **Returns Service** | `:18094` | Returns & RMA (`returns_db`) |
| **Billing Service** | `:18095` | Invoices & Billing (`billing_db`) |
| **Control Center** | `:18100` | Local Web Dashboard (`scripts/start.sh`) |
| **Mailpit Web** | `:18025` | Test mailbox UI (SMTP port `:1025`) |
| **Prometheus** | `:19090` | Metrics scraper |
| **Grafana** | `:13000` | Monitoring dashboards |"""
        else:
            return """### 🌐 Wykaz portów mikroserwisów i infrastruktury MiniTech:

| Komponent | Port | Opis i Baza Danych |
| :--- | :---: | :--- |
| **Frontend Sklepu** | `:18070` | Interfejs klienta w przeglądarce |
| **API Gateway** | `:18080` | Główna brama wejściowa Spring Cloud Gateway |
| **Product Service** | `:18081` | Katalog produktów (`product_db`) |
| **Order Service** | `:18082` | Składanie zamówień + Outbox (`order_db`) |
| **Inventory Service** | `:18083` | Stany magazynowe i rezerwacje (`inventory_db`) |
| **Payment Service** | `:18084` | Płatności i symulacja transakcji (`payment_db`) |
| **Notification Service** | `:18086` | Wysyłka powiadomień e-mail (`notification_db`) |
| **Customer Service** | `:18087` | Rejestracja, logowanie i tokeny JWT (`customer_db`) |
| **Delivery Service** | `:18088` | Śledzenie dostaw i przesyłek (`delivery_db`) |
| **Promotion Service** | `:18089` | Kody rabatowe i promocje (`promotion_db`) |
| **Wishlist Service** | `:18090` | Listy życzeń klientów (`wishlist_db`) |
| **Review Service** | `:18091` | Opinie i oceny produktów (`review_db`) |
| **Recommendation Service** | `:18092` | Rekomendacje powiązanych produktów (`recommendation_db`) |
| **Chat Service** | `:18093` | Czat klienta ze sklepem (`chat_db`) |
| **Returns Service** | `:18094` | Obsługa zwrotów i reklamacji (`returns_db`) |
| **Billing Service** | `:18095` | Faktury i rozliczenia (`billing_db`) |
| **Centrala MiniTech** | `:18100` | Panel kontrolny Web (`bash ./scripts/start.sh`) |
| **Mailpit Web** | `:18025` | Skrzynka testowa e-mail (SMTP port `:1025`) |
| **Prometheus** | `:19090` | Zbieranie metryk aplikacji |
| **Grafana** | `:13000` | Pulpity monitoringu |"""

    # 8. Outbox & Redis Streams
    if any(k in q for k in ["outbox", "zdarzen", "event", "redis", "stream", "broker", "komunikacj"]):
        if is_en:
            return """### ⚡ Event-Driven Communication & Transactional Outbox:

1. **Transactional Outbox Pattern:**
   - When an event occurs (e.g., `OrderCreated`), the service saves both the entity state and the event record into the `outbox_events` table within a **single database transaction**.
   - This ensures atomicity: if the database transaction succeeds, the event is guaranteed to be recorded.

2. **Redis Streams as Message Broker:**
   - A background outbox poller reads pending events from `outbox_events` and publishes them to **Redis Streams** (`stream:events`).
   - Downstream microservices consume events using Redis Consumer Groups.

3. **Inspection & Control:**
   - In Centrala (Operations or CLI), run `stream-info` or `stream-read` to inspect recent event entries."""
        else:
            return """### ⚡ Komunikacja asynchroniczna i wzorzec Transactional Outbox:

1. **Wzorzec Transactional Outbox:**
   - Gdy dochodzi do zmiany stanu (np. utworzenie zamówienia w `Order Service`), rekord biznesowy oraz zdarzenie w tabeli `outbox_events` są zapisywane w **jednej atomowej transakcji SQL**.
   - Gwarantuje to brak utraty zdarzeń nawet przy awarii sieci lub brokera.

2. **Redis Streams jako broker zdarzeń:**
   - W tle wątek procesora Outbox odczytuje nieopublikowane wpisy i przesyła je do strumienia **Redis Streams** (`stream:events`).
   - Zainteresowane serwisy nasłuchują na strumieniu przy użyciu dedykowanych grup konsumentów (*Consumer Groups*).

3. **Diagnostyka w Centrali:**
   - W zakładce **Operacje** możesz podejrzeć strumień poleceniami: `stream-info`, `stream-read`, `dlq-list` i `dlq-replay`."""

    # 13. Testing & QA
    if any(k in q for k in ["test", "verify", "playwright", "smoke", "jakość", "e2e"]):
        if is_en:
            return """### 🧪 Testing & Quality Gates:

1. **App Verify (Purchasing Scenario):**
   - Tests end-to-end checkout: customer registration, product catalog, cart, stock reservation, payment processing, delivery creation, email notification.
   - Run from Dashboard tile **`App Verify`** or CLI: `app-verify`.

2. **Smoke Test:**
   - Runs fast health checks on all endpoints: `test-smoke`.

3. **Playwright Multi-Browser E2E:**
   - Automated UI browser regression across Chromium, Firefox, WebKit: `test-e2e`."""
        else:
            return """### 🧪 Testy i weryfikacja jakości platformy:

1. **App Verify (Kompletny scenariusz zakupowy):**
   - Weryfikuje pełną ścieżkę: rejestrację klienta, pobranie katalogu, koszyk, blokadę magazynową, symulację płatności, wygenerowanie dostawy i wysyłkę e-maila.
   - Kliknij kafelek **`App Verify`** na Pulpicie lub polecenie `app-verify` w Operacjach.

2. **Smoke Test:**
   - Szybka weryfikacja dostępności HTTP wszystkich endpointów: `test-smoke`.

3. **Playwright E2E (Wieloprzeglądarkowe testy UI):**
   - Automatyczne testy przeglądarkowe w Chromium, Firefox i WebKit: `test-e2e`."""

    # 14. Docker & Start/Stop
    if any(k in q for k in ["docker", "compose", "kontener", "uruchom", "start", "stop"]):
        if is_en:
            return """### 🐳 Docker & Running the Platform:

1. **Start all services in Docker:**
   - Click **`▶ Start Services (Docker)`** or **`🚀 1-Click Fresh Start`** in Centrala.
   - CLI command: `bash ./scripts/control-center/control-center.sh docker-apps-up`

2. **Start databases & infrastructure:**
   - Click **`◫ Databases`** in Centrala (`infra-up`).

3. **Stop services:**
   - Click **`⏹ Stop Services`** in Centrala (`docker-apps-down`)."""
        else:
            return """### 🐳 Uruchamianie platformy w Dockerze i procesach:

1. **Uruchomienie wszystkich 15 mikroserwisów i frontendu:**
   - Kliknij **`▶ Uruchom serwisy (Docker)`** lub **`🚀 Pełny start (1-Click)`** na Pulpicie.
   - Lub z terminala: `bash ./scripts/control-center/control-center.sh docker-apps-up`.

2. **Uruchomienie baz danych (PostgreSQL, Redis, Mailpit):**
   - Kliknij **`◫ Bazy danych`** w Centrali (`infra-up`).

3. **Zatrzymanie serwisów:**
   - Kliknij **`⏹ Zatrzymaj serwisy`** w Centrali (`docker-apps-down`)."""

    # 11. Security, Secrets & SOPS
    if any(k in q for k in ["secret", "sekret", "sops", "age", "hasł", "password", "jwt", "klucz"]):
        if is_en:
            return """### 🔐 Security, SOPS & Secret Management:

1. **Age Encryption:**
   - Secrets are encrypted with Mozilla SOPS using age recipient keys stored in `~/.config/mini-order-platform/keys/`.
2. **Encrypted Files:**
   - `dev.env.sops` and `test.env.sops` contain database passwords, JWT private keys, and external service credentials.
3. **Safe Editing in Centrala:**
   - Use the **`DEV Secrets`** or **`TEST Secrets`** quick actions in Centrala."""
        else:
            return """### 🔐 Zarządzanie sekretami i szyfrowanie SOPS:

1. **Szyfrowanie kluczami Age:**
   - Wszystkie hasła baz danych i klucze prywatne są szyfrowane narzędziem **Mozilla SOPS** przy użyciu kluczy `age` w `~/.config/mini-order-platform/keys/`.
2. **Pliki `.env.sops`:**
   - Profile `dev` i `test` posiadają własne pliki `dev.env.sops` i `test.env.sops`.
3. **Edycja w Centrali:**
   - Kliknij kafelki **`Sekrety DEV`** lub **`Sekrety TEST`** w Centrali."""

    if is_en:
        return f"""### ✦ MiniTech Assistant Response

I received your question about **Mini Order Platform**:
> *"{query}"*

**Key Architectural Context:**
- **Monorepo:** 15 Spring Boot 3.4 microservices (Java 25) + vanilla JS Storefront frontend (:18070).
- **Communication:** Synchronous REST through API Gateway (:18080) and Asynchronous Event-Driven via Redis Streams with Transactional Outbox pattern.
- **Databases:** 14 isolated PostgreSQL instances, encrypted secrets via Mozilla SOPS and age keys.
- **Control Center:** Web UI at `:18100` (`scripts/start.sh`) for Docker containers, tests, jobs, diagnostics, and customer chat.

*Tip: You can configure an external AI engine (OpenAI Codex, Google Gemini, or Cursor/Local LLM) in the **⚙ AI Settings** dialog above.*"""
    else:
        return f"""### ✦ Asystent MiniTech

Otrzymałem Twoje pytanie dotyczące projektu **Mini Order Platform**:
> *„{query}”*

**Kluczowe informacje o systemie:**
- **Struktura:** 15 mikroserwisów Spring Boot 3.4 (Java 25) + frontend sklepu (:18070).
- **Komunikacja:** Synchroniczny REST przez API Gateway (:18080) oraz asynchroniczne zdarzenia przez Redis Streams z wzorcem Transactional Outbox.
- **Bazy danych:** 14 niezależnych baz PostgreSQL z szyfrowaniem sekretów SOPS + age.
- **Centrala:** Panel kontrolny na porcie `:18100` (`scripts/start.sh`) do zarządzania kontenerami, bazami, logami, testami i obsługą klienta.

*Wskazówka: Możesz skonfigurować zewnętrzny silnik AI (OpenAI Codex, Google Gemini lub Cursor / Local LLM) w oknie **⚙ Ustawienia AI** na górze strony.*"""

def ai_request(services, url: str, data: bytes, headers: dict[str, str]):
    # LLM credentials must not become platform credentials or follow redirects.
    request = urllib.request.Request(url, data=data, method='POST')
    for name, value in headers.items():
        if name.lower() in {'authorization', 'x-goog-api-key'}: request.add_unredirected_header(name, value)
        else: request.add_header(name, value)
    return request

def prepare_ai_delivery(services, message, history, lang="pl"):
    return cc_ai_delivery.prepare(services.load_ai_config(), services.get_project_system_prompt, message, history, lang)


def open_ai_request(services, request):
    return cc_ai_delivery.open_request(request)


def handle_ai_chat(services, message, history, lang="pl", approval=None, prepared=None):
    plan = prepared or prepare_ai_delivery(services, message, history, lang)
    return cc_ai_delivery.send(services, plan, approval, message, lang)

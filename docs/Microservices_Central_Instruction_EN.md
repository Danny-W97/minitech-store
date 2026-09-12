# Microservices Central Instruction — English Edition

> Polish version: [Microservices_Central_Instruction.md](Microservices_Central_Instruction.md)
>
> Architecture and application manual:
> [General_Instruction_EN.md](General_Instruction_EN.md)

## Status and scope

This manual explains how to operate Mini Order Platform through **Control
Center**, from first-time environment preparation to daily diagnostics and
business operations.

Control Center is a local WSL operations console. By default it opens a dark
web dashboard at `http://127.0.0.1:18100`; the complete terminal interface is
still available as a compatibility mode. It supports the local `dev` and
`test` profiles. The `prod` profile is
currently **unavailable for execution**; its menu can only validate the
prepared Kubernetes, OpenBao, and TLS templates.

Use this document for commands, menus, startup, shutdown, testing, backups,
and operator workflows. Use the general manual for service responsibilities,
data ownership, and end-to-end application flows.

## Contents

- [1. What Control Center is](#1-what-control-center-is)
- [2. Files and local runtime data](#2-files-and-local-runtime-data)
- [3. Starting Control Center](#3-starting-control-center)
- [4. Environment checks](#4-environment-checks)
- [5. Profiles and the SOPS vault](#5-profiles-and-the-sops-vault)
- [6. Infrastructure](#6-infrastructure)
- [7. Applications and frontend](#7-applications-and-frontend)
- [8. Interactive menu map](#8-interactive-menu-map)
- [9. Tests, diagnostics, and quality](#9-tests-diagnostics-and-quality)
- [10. Domain operations](#10-domain-operations)
- [11. PostgreSQL backups](#11-postgresql-backups)
- [12. GitHub safety check](#12-github-safety-check)
- [13. Production preparation](#13-production-preparation)
- [14. Daily procedure](#14-daily-procedure)

## 1. What Control Center is

Control Center is the recommended local operations entry point. Its web UI
uses the MiniTech Store visual language with a darker operator theme. It can:

- detect WSL, Linux, or macOS and verify required tools;
- switch the active Spring profile between `dev` and `test`;
- create, edit, validate, and load SOPS + age secrets;
- start and stop PostgreSQL, Redis, Mailpit, monitoring, applications, and the
  frontend;
- display service status, health, logs, addresses, OpenAPI links, Redis
  streams, and DLQ entries;
- run Maven, integration, contract, security, load, DAST, API E2E, and
  Playwright tests;
- create encrypted PostgreSQL backups and run restore tests;
- operate JWT rotation, audit logs, password reset, promotions, reviews,
  deliveries, chat, returns, complaints, and invoices;
- validate production templates without pretending that production is ready.

The web dashboard provides `Dashboard`, `Services` (with real-time RAM/CPU metrics),
`Operations`, `Data & Store` (orders, invoices, corrections, inventory, customers),
`Events & DLQ` (Redis Streams, Outbox, DLQ, Trace Timeline), `Failure Simulator` (Chaos Engineering),
`Customer chat`, `AI Assistant`, `Tests`, `Jobs and logs`, and `WSL terminal` views. Commands are selected
from a server-side allow-list; the backend does not accept arbitrary shell
commands. It binds only to `127.0.0.1` and state-changing requests require a
local session token.

### Terminal compatibility conventions

- `0` returns to the previous menu.
- `exit` or `quit` closes the entire panel.
- Enter accepts the displayed default for an optional prompt.
- Do not run several Control Center instances against the same environment.
- Secret values, passwords, and tokens are masked in status output.
- Data-changing operations request explicit confirmation, an identifier, a
  status, or a confirmation word such as `RESET`.

The panel is layered. The main menu selects an operational area; its submenu
selects a concrete action. Interactive menu numbers are convenient for manual
work, while direct command names such as `returns-list`, `invoice-show`, and
`promotion-code-create` are the stable interface for automation and CI.

| Area | Purpose |
| --- | --- |
| PostgreSQL databases | start, stop, shell, logs, backup, restore, demo reset |
| Projects and applications | processes, tests, security, store operations, artifacts |
| Redis and streams | event broker, consumer groups, stream inspection |
| Profiles and Mailpit | profile selection, secrets, test e-mail, DAST |
| Monitoring | Prometheus, Grafana, Notification DLQ |
| Frontend | local HTTP server and logs |
| Checks | requirements, diagnostics, GitHub publication safety |
| Production preparation | validation only; no production startup |

## 2. Files and local runtime data

Run commands from the repository root:

```text
Mini Order Platform/
├── compose.yaml
├── docker-compose.monitoring.yaml
├── frontend/
├── scripts/
│   ├── start.sh
│   ├── start.ps1
│   ├── control-center/control-center.sh
│   ├── control-center/web/
│   │   ├── server.py
│   │   ├── index.html
│   │   ├── styles.css
│   │   └── app.js
│   ├── frontend-server.py
│   ├── start-dev.ps1
│   └── stop-dev.ps1
├── docs/
│   ├── General_Instruction_EN.md
│   └── Microservices_Central_Instruction_EN.md
└── gitIgnoreOwn/
    └── runtime/
```

| Item | Location or address |
| --- | --- |
| Bash launcher | `scripts/start.sh` |
| Windows-to-WSL launcher | `scripts/start.ps1` |
| Main Control Center script | `scripts/control-center/control-center.sh` |
| Operator dashboard | `http://127.0.0.1:18100` |
| Dashboard backend | `scripts/control-center/web/server.py` |
| Runtime data | `gitIgnoreOwn/runtime/` |
| Application logs | `gitIgnoreOwn/runtime/logs/` |
| WSL SOPS + age vault | `/home/<user>/.config/mini-order-platform/` |
| Frontend | `http://127.0.0.1:18070` |
| API Gateway | `http://127.0.0.1:18080` |

Never commit real secrets, age or JWT private keys, private certificates,
kubeconfig files, backups, logs, tokens, or generated reports. Depending on
their type, keep them outside the repository or under the ignored
`gitIgnoreOwn/` directory.

## 3. Starting Control Center

### WSL from Windows — recommended

From PowerShell in the project root:

```powershell
.\scripts\start.ps1
```

The launcher opens a separate WSL terminal, starts the local Control Center
server, and opens the dashboard in the default browser. You can also enter WSL
manually:

```bash
cd "/mnt/c/Dysk D/stuff/Mini Order Platform"
bash ./scripts/start.sh
```

With no arguments, this command starts the graphical dashboard. Keep its WSL
terminal open. `Ctrl+C` stops the dashboard server only; it does not
automatically stop running containers or application processes.

Start the original terminal menu with:

```bash
bash ./scripts/start.sh --cli
```

Headless startup and an alternative local port are also supported:

```bash
bash ./scripts/start.sh --no-browser
bash ./scripts/start.sh --port 18101
```

### Using the web dashboard

- `Dashboard` shows the active profile, online services, and frequent actions.
- `Services` checks the minimal Actuator endpoint of all 15 applications. Each service card displays response latency and real-time container metrics: RAM usage and CPU percentage (`🧠 [RAM] / [Limit] • [CPU]%`).
- `Operations` exposes allow-listed actions structured into clear functional groups:
  - **Apps:** microservices lifecycle management. Features a distinct separation between **Docker containers** (`docker-apps-up`, `docker-apps-down`, `docker-apps-status`) and **local WSL Maven processes** (`app-run-all`, `app-stop-all`, `app-status-all`, `app-run`, `app-stop`).
  - **Building:** monorepo packaging and image build operations (`app-test`, `app-package`, `images-build`). Redundant container start/stop cards were removed to maintain focus solely on build tools.
  - **API documentation:** direct tile linking to aggregated Swagger UI (`swagger-ui`) and Resilience4j metrics (`resilience-status`).
  - **Infrastructure:** unified start and stop for all 14 PostgreSQL databases, Redis broker, and Mailpit (`infra-up`, `infra-down`).
  - **Databases, Redis & Events, Mailpit, Backups, Security, Store operations, Profiles & secrets, Preflight & Diagnostics, Prod — validation only, Settings.**
- `Data & Store` — comprehensive business data explorer:
  - **Orders:** live orders table from `order-service`, status filters (`ALL`, `COMPLETED`, `PAID`, `PENDING`, `CANCELLED`), multi-column sorting, and quick invoice generation via `➕ Issue`.
  - **Invoices:** complete invoice ledger from `billing-service`. Features KPI summary cards (Total Invoices, Net, VAT, Gross amounts), multi-column sorting, bulk ZIP export (`📦 Export ZIP`), JSON inspection, PDF download, and quick email dispatch shortcuts (`✉️`). For corrected invoices, it utilizes **Model A (Accordion Sub-row)** — clicking `[📜 X corrs. ▼]` expands the correction history with delta amounts, reasons, and individual PDF/JSON downloads. The `📝 Correction` button opens an interactive modal with a real-time calculator to issue new credit notes (KOR).
  - **Document Dispatch (✉️ Wysyłka dokumentów):** dedicated communications center for dispatching accounting documents (standard invoices `FV` and credit notes `KOR`) to customers via SMTP (Mailpit). Features document type filters (`All`, `Invoices FV`, `Credit Notes KOR`), real-time search, recipient email overrides (e.g. sending directly to accounting offices), optional **custom notes/messages to the customer** embedded in the HTML email body, and automatic PDF attachment. Delivery status is persistently tracked in Control Center (`🟢 Sent (date)` / `⚪ Ready`).
  - **Inventory:** live stock levels, reserved quantities, and quick restock buttons (`+10`, `+50`, `+100`).
  - **Customers:** registered customer accounts, contact information, status, and company Tax IDs (NIP).
- `Events & DLQ` — asynchronous messaging and resilience monitoring:
  - **Redis Stream:** real-time stream inspection with a toggle between `payment-events` (published by Payment Service) and `invoice-events` (EDA invoicing published by Billing Service). In `invoice-events`, `INVOICE_CORRECTED` records explicitly display the gross delta amount and the final amount after correction (e.g. `-289.00 PLN (after: 2821.47 PLN)`) with an amber status badge. Technical seed records that initialize the stream on startup are prominently badged as `BOOTSTRAP`.
  - **Outbox Events:** live inspection of `payment_outbox_events` (Transactional Outbox) ensuring *At-Least-Once Delivery*.
  - **Dead Letter Queue (DLQ):** failed notification messages from `notification_db` with an immediate `Replay` button.
  - **Trace Timeline:** distributed transaction visualizer for tracking flows across services by `correlationId`.
- `Failure Simulator` — Chaos Engineering module enabling fault injection (latency delays and HTTP 500 errors) into target microservices to test resilience patterns (Circuit Breaker, Retry, Fallback).
- `Customer chat` lists conversations and replies without manually entering an
  ID; the reply is also passed to Notification Service.
- `AI Assistant` (MiniTech Architecture Assistant) — interactive assistant supporting developers with architecture knowledge, database schemas, routing, and troubleshooting:
  - **Default Built-in Mode (Offline) & Top Prompt Chips:**
    - Without integrating an external agent or CLI model, the AI assistant operates in its **default built-in (offline) mode**.
    - It is driven by the embedded monorepo domain knowledge engine and **quick action buttons (prompt chips)** placed at the top of the view containing **frequently asked questions** (e.g. *Orders in DB*, *Service health*, *Product catalog*, *VAT invoices*, *Discount codes*, *Customer chat*, *Docker & Containers*, *Microservice ports*, *Outbox & Redis*, *SOPS Secrets*, *Tests & Quality*, *Error knowledge base*).
    - This mode operates 100% locally, private, zero-cost, requiring no API keys, internet connectivity, or external processes.
  - **Conversational Chat Mode (Requires External Agent):**
    - If you wish to use the AI Assistant as an **open conversational chat** for answering arbitrary natural language prompts, **you must connect an external LLM or agent** (such as a local model from your computer via Ollama `http://localhost:11434/v1`, Google Gemini API, or OpenAI Codex).
    - This clear distinction ensures users are not misled regarding the capabilities of the offline rule-based knowledge engine.
  - **Mode Indicator (Status Badge):** dynamic LED indicator displaying active engine status:
    - 🟢 `Built-in Mode (Offline)` — active default local mode operating on chips and rule diagnostics.
    - 🟣 `Custom AI Agent` — indicates an active connection to an external engine (e.g., local `Ollama / qwen2.5-coder`, `OpenAI / gpt-4o`, or `Google Gemini`).
  - **`⚙ AI Settings` Button:** opens the AI engine configuration modal:
    - **Local LLM (Ollama / Local):** connect to any locally hosted model via OpenAI-compatible endpoint (defaults to `http://localhost:11434/v1`).
    - **Google Gemini:** connect to Gemini (`gemini-2.5-flash`) using a free API key from Google AI Studio.
    - **OpenAI Codex:** connect to OpenAI models (`gpt-4o`) with your API key.
    - **Knowledge Base Context:** toggle to automatically inject monorepo architectural context into external agent prompts.
- `Tests` runs the same smoke, E2E, ZAP, and Playwright scripts as the CLI.
- `Jobs and logs` shows process output stored under
  `gitIgnoreOwn/runtime/control-center-web/jobs/`.
- `WSL terminal` embeds the original menu for `secrets-edit`, `db-shell`, Vim,
  and other operations that require an interactive terminal.

### API Documentation & Aggregated Swagger UI (:18080)

The centralized OpenAPI documentation entrypoint for all 15 microservices is hosted directly on the **API Gateway** on port `18080`:

- **Direct URL:** `http://127.0.0.1:18080/swagger-ui.html`
- **Dynamic Service Dropdown (*Select a definition*):** The top-right dropdown allows instant switching of OpenAPI specs across any microservice (`Product Service`, `Order Service`, `Payment Service`, `Billing Service`, `Delivery Service`, etc.).
- **Zero CORS Issues:** Because interactive `Try it out` requests are issued directly through the gateway port `18080`, there are no cross-origin restrictions or header conflicts.
- **Access Points across Dashboard:**
  - `📖 Swagger UI ↗` button in top navigation bar,
  - Dedicated banner and action tile in `Operations → API documentation`,
  - Link in `About platform` view,
  - Command Palette shortcut (`Ctrl+K`).

### Sidebar Navigation & Monitoring Section

The left sidebar of the Control Center includes a dedicated **MONITORING** section restricted exclusively to telemetry and metric observation tools:
- 📊 **Grafana** (`http://127.0.0.1:13000`) — metric dashboards, resource consumption, and JVM telemetry.
- 🔥 **Prometheus** (`http://127.0.0.1:19090`) — Prometheus metric scrapers and scraping target inspection (Targets).
*(Documentation tools like Swagger UI are placed in the top bar and operational views to preserve telemetry purity).*

### Managing the Web Dashboard in the background (Daemon)

The Web Dashboard can be run and controlled as a background daemon process:

```bash
# Start the web dashboard daemon in the background
bash scripts/control-center/control-center.sh web-start

# Check daemon status and PID
bash scripts/control-center/control-center.sh web-status

# Stop the web dashboard daemon
bash scripts/control-center/control-center.sh web-stop
```

The language action changes the classic terminal menu. The graphical operator
dashboard currently uses Polish labels, while its complete manual is also
available in English.

Existing secret values are never read or returned by the dashboard. The
`Create DEV/TEST secrets` and edit operations use dedicated local forms rather
than background jobs: values are not placed in URLs, process arguments, or job
logs. The server encrypts the plaintext profile with SOPS and removes the
temporary file. For existing PostgreSQL volumes, enter the passwords already
used by those volumes.

### New secret-management extensions in the dashboard

The dashboard detects the vault state and presents the correct operation for
each profile:

| Profile state | Operation in `Operations → Profiles and secrets` | Action |
|---|---|---|
| No `*.env.sops` file | `Create DEV secrets` or `Create TEST secrets` | First-time setup, age-key creation, and encrypted profile creation |
| Key and encrypted file exist | `DEV secrets` or `TEST secrets` | Edit selected fields without reading existing values |
| Key or tools are missing | `Initialize age keys` / `Vault status` | Environment preparation and diagnostics |

The first-time setup form requires:

- all fourteen PostgreSQL database passwords;
- `CUSTOMER_JWT_SECRET` as Base64 with at least 32 decoded bytes;
- `GRAFANA_ADMIN_PASSWORD`.

The remaining fields have local-safe defaults: Grafana user `admin`, Mailpit at
`127.0.0.1:11025`, empty SMTP credentials, disabled SMTP Auth and STARTTLS, and
profile-specific sender/support addresses. They can be changed before saving.
If a PostgreSQL volume already exists, enter the password used when that volume
was initialized; changing the secret file alone does not change a database
password.

After confirmation, the form sends values to a dedicated process through
standard input as Base64. Values are not placed in URLs, process arguments, job
logs, or API responses. The plaintext temporary file is encrypted with SOPS
and the profile's age key, then removed. The wizard does not start containers
or microservices. After it succeeds, run validation and startup separately:

```bash
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh infra-up
```

Replace `dev` with `test` for the test profile. If the encrypted file already
exists, first-time setup refuses to overwrite it; use the web editor or the
terminal `secrets-edit` command instead.

### Linux or macOS

```bash
cd "/path/to/Mini Order Platform"
bash ./scripts/start.sh
```

### Direct, non-interactive commands

Every supported operation can be invoked without opening the menu:

```bash
bash scripts/control-center/control-center.sh <command> [arguments]
```

Examples:

```bash
bash scripts/control-center/control-center.sh profile-show
bash scripts/control-center/control-center.sh health all
bash scripts/control-center/control-center.sh app-logs customer
```

### IntelliJ IDEA

The full environment launcher can execute:

```text
scripts/control-center/ide-environment.ps1 dev
```

To keep each service log visible in a separate IntelliJ console, create Shell
Script run configurations that use `scripts/control-center/ide-run.ps1`, for
example:

```text
-Service promotion -Profile dev
```

The general manual contains the complete IntelliJ setup and dependency order.

## 4. Environment checks

Before the first start, after moving the repository, or after upgrading WSL,
run:

```bash
bash scripts/control-center/control-center.sh platform-info
bash scripts/control-center/control-center.sh environment-check
bash scripts/control-center/control-center.sh secrets-status
bash scripts/control-center/control-center.sh doctor
```

`environment-check` verifies, among other things:

- Bash, `curl`, Python 3, Git, Java/JDK 25, and `javac`;
- the Docker daemon and Docker Compose;
- Compose files, Maven Wrapper, root POM, and frontend files;
- `sops`, `age`, and `age-keygen`;
- active profile and required secret names;
- database, port, and runtime-directory maps.

`doctor` performs a broader check of already-running containers, databases,
applications, Mailpit, and optional monitoring.

To display an installation plan for missing system tools:

```bash
bash scripts/control-center/control-center.sh environment-install
```

The installer changes nothing until the operator enters `ACCEPT`. It does not
automatically install Docker Desktop integration or grant system permissions.

## 5. Profiles and the SOPS vault

### Selecting a profile

```bash
bash scripts/control-center/control-center.sh profile-show
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh profile-set test
```

Interactive paths:

```text
Main menu → 4. Profiles and Mailpit → 2. Set dev profile
Main menu → 4. Profiles and Mailpit → 3. Set test profile
```

The choice is stored locally in `gitIgnoreOwn/runtime/active-profile` and
passed to applications as `SPRING_PROFILES_ACTIVE`.

### Vault layout

The actual vault is local to the WSL user and is not part of the repository:

```text
/home/<user>/.config/mini-order-platform/
├── keys/dev.agekey
├── keys/test.agekey
└── secrets/
    ├── dev.env.sops
    └── test.env.sops
```

First-time initialization:

```bash
bash scripts/control-center/control-center.sh secrets-init
bash scripts/control-center/control-center.sh secrets-setup dev
bash scripts/control-center/control-center.sh secrets-setup test
```

The same first-time setup is available in the web panel under
`Operations → Profiles and secrets → Create DEV/TEST secrets`. The form creates
the age keys, accepts the required values, encrypts the profile, and does not
start services automatically. The terminal wizard remains available for CLI
users.

Edit an existing profile or create the missing encrypted profile file:

```bash
bash scripts/control-center/control-center.sh secrets-edit dev
bash scripts/control-center/control-center.sh secrets-edit test
```

Validate names and formats without displaying values:

```bash
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh secrets-validate test
```

### Required secret names

| Area | Variables |
| --- | --- |
| Core purchase databases | `PRODUCT_DB_PASSWORD`, `ORDER_DB_PASSWORD`, `INVENTORY_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`, `CUSTOMER_DB_PASSWORD` |
| Store feature databases | `DELIVERY_DB_PASSWORD`, `PROMOTION_DB_PASSWORD`, `WISHLIST_DB_PASSWORD`, `REVIEW_DB_PASSWORD`, `RECOMMENDATION_DB_PASSWORD` |
| Support and document databases | `CHAT_DB_PASSWORD`, `RETURNS_DB_PASSWORD`, `BILLING_DB_PASSWORD` |
| Monitoring | `GRAFANA_ADMIN_PASSWORD` |
| Customer authentication | `CUSTOMER_JWT_SECRET`, or RS256 material: `CUSTOMER_JWT_ACTIVE_KID`, `CUSTOMER_JWT_PRIVATE_KEY_BASE64`, `CUSTOMER_JWT_PUBLIC_KEYS_JSON` |

Standard `secrets-validate`, `infra-up`, and `app-run-all` require all fourteen
database passwords, including Chat, Returns, and Billing. The initial wizard
can create a legacy HMAC JWT setup. Later, `jwt-rotate dev` or
`jwt-rotate test` can create RS256 keys with `kid` and JWKS material.

Local Mailpit keeps `MAIL_USERNAME` and `MAIL_PASSWORD` empty. Values such as
`MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`, `MAIL_SMTP_AUTH`,
`MAIL_SMTP_STARTTLS_ENABLE`, `GRAFANA_ADMIN_USER`, and `CHAT_SUPPORT_EMAIL`
are profile configuration rather than mandatory secrets checked by
`secrets-validate`.

Do not create a plain `.env` file containing real passwords in the repository.
If validation reports a missing variable, use the encrypted editor.

## 6. Infrastructure

### PostgreSQL databases

Display the service/database/port map:

```bash
bash scripts/control-center/control-center.sh db-list
```

Start every database required by the platform:

```bash
bash scripts/control-center/control-center.sh infra-up
```

Operate one database:

```bash
bash scripts/control-center/control-center.sh db-up chat
bash scripts/control-center/control-center.sh db-status chat
bash scripts/control-center/control-center.sh db-logs chat
bash scripts/control-center/control-center.sh db-shell chat
```

Stop databases while preserving volumes:

```bash
bash scripts/control-center/control-center.sh infra-down
```

Stop the main Compose project, including Redis and Mailpit, while preserving
volumes:

```bash
bash scripts/control-center/control-center.sh db-down-all
```

### Redis event broker

```bash
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh broker-status
bash scripts/control-center/control-center.sh stream-info
bash scripts/control-center/control-center.sh stream-read 20
```

Redis hosts the `payment-events` stream and the Notification Service consumer
group. It is also used for login/rate limits and temporary MFA challenges.
Avoid resetting consumer-group metadata unless diagnosing a broken consumer:

```bash
bash scripts/control-center/control-center.sh broker-reset-group notification-service
```

### Mailpit

```bash
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh mailpit-status
bash scripts/control-center/control-center.sh mailpit-list 20
bash scripts/control-center/control-center.sh mailpit-search "subject:MiniTech"
bash scripts/control-center/control-center.sh mailpit-smoke
```

- Web UI: `http://127.0.0.1:18025`
- SMTP: `127.0.0.1:11025`

Mailpit captures messages in `dev` and `test`; it does not deliver them to
real external recipients. `mailpit-send` sends a manual test through
Notification Service. `mailpit-clear` requires additional confirmation.

### Monitoring and DLQ

```bash
bash scripts/control-center/control-center.sh monitoring-up
bash scripts/control-center/control-center.sh monitoring-status
bash scripts/control-center/control-center.sh monitoring-urls
bash scripts/control-center/control-center.sh dlq-list
bash scripts/control-center/control-center.sh dlq-replay <id>
```

Prometheus and Grafana use a separate Compose file. Grafana reads its
administrator password from the active encrypted profile. Notification
failures that exhaust retries can be inspected and replayed from the durable
database-backed DLQ.

## 7. Applications and frontend

### Start all applications in the background

After infrastructure is ready:

```bash
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh app-status-all
bash scripts/control-center/control-center.sh health all
```

`app-run-all` starts all fifteen Spring Boot applications: API Gateway,
Product, Inventory, Order, Payment, Notification, Customer, Delivery,
Promotion, Wishlist, Review, Recommendation, Chat, Returns, and Billing.

### Running microservices and frontend in Docker containers

Alternatively to local Maven processes, all 15 microservices and the frontend can be run in Docker containers using a single `compose.apps.yaml` file:

```bash
# 1. Build local Docker images (if not built yet):
bash scripts/control-center/control-center.sh images-build

# 2. Start all services and frontend in Docker containers with a single command:
bash scripts/control-center/control-center.sh docker-apps-up
# or via standalone script:
bash scripts/start-apps-docker.sh     # (PowerShell: .\scripts\start-apps-docker.ps1)

# 3. Inspect status and logs:
bash scripts/control-center/control-center.sh docker-apps-status
bash scripts/control-center/control-center.sh docker-apps-logs [service_name]

# 4. Stop Docker application containers:
bash scripts/control-center/control-center.sh docker-apps-down
# or via standalone script:
bash scripts/stop-apps-docker.sh      # (PowerShell: .\scripts\stop-apps-docker.ps1)
```

All application containers connect to PostgreSQL databases and Redis via the shared Docker Compose network using internal DNS names (`order-db`, `product-db`, `event-broker`, `mailpit`).

### Start one service

```bash
bash scripts/control-center/control-center.sh app-run product dev
bash scripts/control-center/control-center.sh app-logs product
```

Accepted aliases:

```text
gateway product inventory order payment notification customer delivery
promotion wishlist review recommendation chat returns billing
```

Stop only processes launched by Control Center:

```bash
bash scripts/control-center/control-center.sh app-stop-all
```

This does not stop PostgreSQL, Redis, or Mailpit containers.

### Frontend

```bash
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh frontend-status
bash scripts/control-center/control-center.sh frontend-logs
bash scripts/control-center/control-center.sh frontend-down
```

Open the store at `http://127.0.0.1:18070`.

## 8. Interactive menu map

Open the menu with `bash ./scripts/start.sh`.

### 1. PostgreSQL databases

The database menu includes:

1. database and port map;
2. start all databases;
3. stop all databases;
4. database status;
5. logs for one database;
6. `psql` shell for one database;
7. reset inventory quantities;
8. reset demo data;
9. back up one database;
10. back up all databases;
11. list backups;
12. test restore;
13. remove backups older than the retention period;
14. initialize backup encryption keys;
15. install the backup schedule;
16. remove the backup schedule;
17. show schedule status.

### 2. Projects and applications

This menu is split into five stable operational groups:

1. **Startup and status** — applications, processes, logs, and health checks.
2. **Tests and API quality** — Maven, smoke, App Verify, contract, security,
   load, resilience, E2E, Playwright, and OpenAPI addresses.
3. **Security and accounts** — JWT, key rotation, audit, and password reset.
4. **Store operations** — promotions, reviews, delivery tracking, chat,
   returns, complaints, and invoices.
5. **Build and artifacts** — packaging and local Docker images.

Examples:

- `Projects and applications → Store operations → 1`: list and validate
  promotion codes;
- `→ Store operations → 2`: generate a promotion code;
- `→ Store operations → 7/8`: list and reply to chat messages;
- `→ Store operations → 9`: list returns and complaints;
- `→ Store operations → 10`: change a return/complaint status;
- `→ Store operations → 11`: list all orders with amount, e-mail, items, and
  invoice flag;
- `→ Store operations → 12`: display an invoice;
- `→ Store operations → 13`: download an invoice PDF to local runtime storage.

### 3. Redis event broker and streams

Start, stop, PING, logs, stream information, record reading, and consumer-group
reset.

### 4. Profiles and Mailpit

Profile selection, Mailpit operations, SMTP smoke, message list/search/clear,
vault status, key initialization, secret wizards, encrypted editing, JWT,
audit, password reset, and DAST.

### 5. Monitoring and Dead Letter Queue

Prometheus/Grafana lifecycle, statuses, logs, URLs, DLQ listing, and replay.

### 6. Store frontend

Start, stop, status, logs, and frontend address.

### 7. Docker Compose infrastructure status

A compact status of containers from the main Compose project.

### 8. Settings

`1. Language` selects Polish or English; `2` displays the current language.
The choice is saved under local runtime data and is not committed.

### 9–12. Checks

- `9`: pre-start environment check;
- `10`: optional missing-component installer after entering `ACCEPT`;
- `11`: GitHub publication safety check;
- `12`: full diagnostics.

### 13. Production profile — preparation only

This menu never starts production. It only validates Kubernetes manifests,
OpenBao/TLS preparation, a selected TLS host, and template locations.

## 9. Tests, diagnostics, and quality

Run all commands from the repository root. HTTP/system tests require a valid
profile, infrastructure, services, and sometimes the frontend. Maven unit
tests do not require already-running HTTP services.

### Test map

| Command or suite | What it checks | Required environment | Data effect |
| --- | --- | --- | --- |
| `app-test` / Maven `test` | unit tests and Spring contexts | JDK 25, Maven Wrapper | none |
| Maven `-Pintegration verify` | PostgreSQL, order saga, Redis/Mailpit through Testcontainers | JDK 25, Docker | temporary containers |
| `smoke` | Gateway → Product → catalogue | Gateway, Product | read-only |
| `contract-test` | public API v1 against Gateway OpenAPI | Gateway | read-only |
| `security-test` | JWT boundaries, invalid Bearer, safe Actuator payload, secret exposure | Gateway, Customer | read-only |
| `load-test` | default 100 catalogue requests, 10 concurrent | Gateway, Product | read-only load |
| `resilience-test` | Chaos test simulating service failure (e.g. payment-service) | Running environment (test/dev profile) | Temporarily stops target service, auto-restores afterwards |
| `delivery-smoke` / `promotion-smoke` | delivery quotes and active campaign | Delivery/Promotion | read-only |
| `mailpit-smoke` | Notification → SMTP Mailpit | Notification, Redis, Mailpit | adds a message |
| `app-verify` | order → payment → inventory → e-mail | full backend, Redis, Mailpit | creates an order and changes stock |
| `e2e` | registration, verification, JWT, recommendation, checkout, e-mail | complete environment | creates account/order/messages |
| `ui-test-smoke` | three critical UI paths in Chromium | Node.js 20+, Playwright | mocked API, no DB changes |
| `ui-test` | catalogue, account, cart, checkout, chat, responsive UI | Node.js 20+, Playwright | mocked API, no DB changes |
| `ui-test-system` | browser checkout through real services | complete environment and frontend | creates order/e-mail |
| `zap-dast` | active OWASP ZAP scan against Gateway | Gateway, Docker | report under ignored runtime |

### Prepare system tests

```bash
bash scripts/control-center/control-center.sh environment-check
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh infra-up
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh health all
```

For `test`, replace `dev` in profile and secret commands. Full validation must
include Chat, Returns, and Billing database passwords.

### Common test commands

```bash
bash scripts/control-center/control-center.sh app-test all
bash scripts/control-center/control-center.sh app-package all
bash scripts/control-center/control-center.sh smoke
bash scripts/control-center/control-center.sh app-verify
bash scripts/control-center/control-center.sh e2e
bash scripts/control-center/control-center.sh contract-test
bash scripts/control-center/control-center.sh security-test
bash scripts/control-center/control-center.sh load-test
bash scripts/control-center/control-center.sh zap-dast
bash scripts/control-center/control-center.sh resilience-status
bash scripts/control-center/control-center.sh api-docs-urls
```

### Smoke versus App Verify

`smoke` is a fast routing test. It sends `GET /api/products` to Gateway, which
forwards the request to Product Service. Success proves that the entry point
and catalogue answer; it says nothing about payment, inventory, or e-mail.

`app-verify` is a mutating MVP scenario. It checks health, obtains delivery
methods, creates an order, performs simulated payment, confirms inventory
reservation, verifies stock changes, checks delivery data, Notification
Service, and Mailpit.

```bash
APP_VERIFY_PRODUCT_ID=4 APP_VERIFY_QUANTITY=1 \
  bash scripts/control-center/control-center.sh app-verify
```

After repeated demonstration tests, use the demo reset operation and confirm
it by entering `RESET`.

### Full API E2E

```bash
bash scripts/control-center/control-center.sh e2e
```

The suite creates a unique `e2e-...@minitech.local` account through Gateway,
finds its verification link in Mailpit, verifies the account, exercises login,
`/customers/me`, refresh, and logout, checks recommendations, and then runs the
purchase verification. Passwords and tokens stay hidden; temporary files are
removed. Created domain data remains until demo reset.

### Playwright UI tests

Playwright is the browser test framework used by the project. Cypress is not
installed because it would duplicate the same browser scenarios and fixtures.
The ordinary regression starts the static server
`scripts/qa/frontend-server.mjs` and mocks `/api/**`; it therefore needs no
Docker, secrets, databases, or running microservices.

```bash
bash scripts/control-center/control-center.sh ui-test-status
bash scripts/control-center/control-center.sh ui-test-install
bash scripts/control-center/control-center.sh ui-test-smoke
bash scripts/control-center/control-center.sh ui-test
bash scripts/control-center/control-center.sh ui-test-system
bash scripts/control-center/control-center.sh ui-test-all
bash scripts/control-center/control-center.sh ui-test-headed
bash scripts/control-center/control-center.sh ui-test-debug
bash scripts/control-center/control-center.sh ui-test-report
```

Recommended procedure:

1. Run `ui-test-status` to verify Node.js, package manager, dependencies, and
   detected spec files.
2. On first use, run `ui-test-install` to install locked packages and Chromium.
3. Run `ui-test-smoke` for fast feedback or `ui-test` for the Chromium suite.
4. Use `ui-test-system` only after the full local environment and frontend are
   healthy.
5. Open the HTML report with `ui-test-report` and browse
   `http://127.0.0.1:19325`; stop the report server with `Ctrl+C`.

Node.js 20 or newer is required; Node.js 22 LTS is recommended. The full
multi-browser command also installs/runs Firefox, WebKit, and mobile emulation.
Headed and debug modes require WSLg or another GUI. Reports and traces are
stored under `gitIgnoreOwn/runtime/playwright/`.

### Direct execution without Control Center

```bash
./mvnw test
./mvnw -Pintegration verify
bash scripts/qa/contract-test.sh
bash scripts/qa/security-test.sh
LOAD_REQUESTS=100 LOAD_CONCURRENCY=10 bash scripts/qa/load-test.sh
bash scripts/qa/e2e.sh
bash scripts/qa/zap-dast.sh
pnpm run test:ui:smoke
pnpm run test:ui
pnpm run test:ui:browsers
```

The complete CI scenario is `scripts/qa/ci-e2e.sh`, used by
`.github/workflows/ci.yml`. It creates an isolated Compose environment and
runs contract, security, ZAP, load, API E2E, and Playwright checks. Do not run
it concurrently with a local environment using the same ports.

### Diagnosing a failed test

```bash
bash scripts/control-center/control-center.sh app-status-all
bash scripts/control-center/control-center.sh health all
bash scripts/control-center/control-center.sh app-logs <service>
bash scripts/control-center/control-center.sh doctor
```

Use `health all` for every HTTP application, `smoke` for Gateway routing, and
`app-verify` for the complete order/payment/inventory/e-mail flow.

## 10. Domain operations

### Accounts and security

```bash
bash scripts/control-center/control-center.sh jwt-status
bash scripts/control-center/control-center.sh jwt-smoke
bash scripts/control-center/control-center.sh jwt-rotate dev
bash scripts/control-center/control-center.sh audit-list 50
bash scripts/control-center/control-center.sh password-reset-request user@example.test
```

`jwt-smoke` requests a password without echoing it and checks login, `/me`,
refresh, and logout. JWT status never prints token or key values.

### Delivery tracking

```bash
bash scripts/control-center/control-center.sh delivery-smoke
bash scripts/control-center/control-center.sh delivery-tracking 1
bash scripts/control-center/control-center.sh delivery-tracking-status 1 DELIVERED
```

For pickup, use `READY_FOR_PICKUP` followed by `PICKED_UP`. For shipping, use
`LABEL_CREATED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, and `DELIVERED`.

The customer selects a delivery/pickup date in checkout. Control Center does
not choose the date manually; it displays quotes/slots and later inspects or
demonstrates tracking status. Cash on delivery/pickup stays `PENDING` until
payment handling, so it does not immediately generate a tracking number.

### Promotions and reviews

```bash
bash scripts/control-center/control-center.sh promotion-smoke
bash scripts/control-center/control-center.sh promotion-codes
bash scripts/control-center/control-center.sh promotion-code-create
bash scripts/control-center/control-center.sh review-pending
bash scripts/control-center/control-center.sh review-moderate 1 PUBLISHED
```

Promotion code creation asks for a code or generates one, description,
percentage discount, optional redemption limit, validity dates, and active
status. A code is validated during checkout and its redemption counter is
increased only after successful payment.

### Customer chat

```bash
bash scripts/control-center/control-center.sh chat-list 50
bash scripts/control-center/control-center.sh chat-reply 1 "Thank you for contacting us."
```

A reply must go through Chat Service. Writing a message directly in Mailpit
does not append it to the conversation and the customer will not see it in the
live chat widget.

### Returns and complaints

```bash
bash scripts/control-center/control-center.sh returns-list
bash scripts/control-center/control-center.sh returns-status 1 UNDER_REVIEW "Request verification"
```

List requests first, copy the identifier, then set an allowed status and an
operator note. Re-run `returns-list` to verify the result.

### Invoices and Corrections (Billing Service)

```bash
bash scripts/control-center/control-center.sh orders-list 100
bash scripts/control-center/control-center.sh invoice-show 1
bash scripts/control-center/control-center.sh invoice-pdf 1
bash scripts/control-center/control-center.sh invoices-export-zip
bash scripts/control-center/control-center.sh invoice-correct 1 "Post-transaction discount" -50.00
bash scripts/control-center/control-center.sh corrections-list
```

`orders-list` reads through API Gateway and shows the order ID, status, amount,
recipient, line items with quantities, and the invoice-request flag.
`invoice-show` displays invoice data. `invoice-pdf` downloads and saves the PDF under
`gitIgnoreOwn/runtime/invoices/`. The `invoices-export-zip` command exports all issued invoice PDFs into a compressed archive under
`gitIgnoreOwn/runtime/invoices_export_<timestamp>.zip`. The
`invoice-correct <id> "<reason>" <gross_delta>` command issues a credit note (KOR) with the specified delta amount (negative value for discounts/refunds), and `corrections-list` displays all registered credit notes. In Control Center Web, operators can navigate to the dedicated **Document Dispatch** tab (or click the `✉️` shortcut in the invoice table) to send or resend any invoice or credit note via email with an optional custom message to the customer (e.g. discount reason or refund ETA) and the PDF attached. Messages arrive directly in Mailpit (`http://127.0.0.1:18025`). After a new paid order, Mailpit should also contain the initial invoice PDF as an attachment on the payment-confirmation message. Orders
created before the `invoiceRequested` migration may show `NO` when the original
invoice choice cannot be reconstructed from Order Service data.

The identifier is the order/invoice identifier expected by Billing Service.
Invoice data is a snapshot created for the paid order and should not be edited
directly in PostgreSQL.

## 11. PostgreSQL backups

Initialize age keys used to encrypt local backups:

```bash
bash scripts/control-center/control-center.sh backup-key-init all
```

Create and list backups:

```bash
bash scripts/control-center/control-center.sh db-backup all
bash scripts/control-center/control-center.sh db-backup product
bash scripts/control-center/control-center.sh db-backup-list all
```

Test restoration and apply retention:

```bash
bash scripts/control-center/control-center.sh db-restore-test product
bash scripts/control-center/control-center.sh db-backup-prune 30
```

Install or remove the WSL/Linux schedule:

```bash
bash scripts/control-center/control-center.sh backup-schedule-install 2
bash scripts/control-center/control-center.sh backup-schedule-status
bash scripts/control-center/control-center.sh backup-schedule-remove
```

Backups are local and encrypted. Verify that the backup directory is outside
Git tracking before publishing the repository.

## 12. GitHub safety check

Before `git add`, run:

```bash
bash scripts/control-center/control-center.sh github-check
git status --short --ignored
```

The check looks for `.env` files, real SOPS vaults, age/JWT keys, private
certificates, kubeconfig files, backups, logs, generated reports, and build
directories. Review the result before staging or committing anything.

## 13. Production preparation

Do not try to start production with:

```bash
bash scripts/control-center/control-center.sh profile-set prod
bash scripts/control-center/control-center.sh app-run-all
```

A real production deployment requires a target Kubernetes cluster, image
registry, OpenBao, TLS, external mail delivery, key rotation, production
databases, and separate operational infrastructure. The repository currently
provides validation only:

```bash
bash scripts/control-center/control-center.sh prod-status
bash scripts/control-center/control-center.sh k8s-validate
bash scripts/control-center/control-center.sh openbao-validate
bash scripts/control-center/control-center.sh openbao-status
bash scripts/control-center/control-center.sh tls-status example.test
bash scripts/control-center/control-center.sh k8s-status
```

Passing these checks means templates are structurally prepared. It does not
mean that a production environment exists or is safe to expose publicly.

## 14. Daily procedure

### Start

```bash
cd "/mnt/c/Dysk D/stuff/Mini Order Platform"
bash scripts/control-center/control-center.sh secrets-validate dev
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh infra-up
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh health all
```

Optional services:

```bash
bash scripts/control-center/control-center.sh monitoring-up
bash scripts/control-center/control-center.sh monitoring-urls
```

### Stop

```bash
bash scripts/control-center/control-center.sh frontend-down
bash scripts/control-center/control-center.sh app-stop-all
bash scripts/control-center/control-center.sh monitoring-down
bash scripts/control-center/control-center.sh infra-down
```

Use `db-down-all` instead of `infra-down` when you also want to stop the Redis
and Mailpit containers from the main Compose project. Volumes remain intact.

For the architecture, service-to-service communication, data ownership,
checkout saga, JWT flow, and feature explanations, continue with
[General_Instruction_EN.md](General_Instruction_EN.md).

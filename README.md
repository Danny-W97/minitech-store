# Mini Order Platform

> The `dev` and `test` profiles are available locally through Control Center on
> WSL/Linux/macOS.
> The `prod` profile is currently unavailable for local execution. The repository
> contains only its Kubernetes/OpenBao/TLS preparation templates.

Local secrets, keys, certificates, kubeconfig files, backups and logs must be
kept in the ignored `gitIgnoreOwn/` directory. The actual SOPS + age vaults for
the `dev` and `test` profiles remain outside the repository. On Windows, the
vault is stored in WSL; on Linux/macOS, it is stored in a directory selected by
the platform adapter.

Control Center contains a separate `Profile prod - preparation (UNAVAILABLE)`
section used only to validate and diagnose the Kubernetes/OpenBao/TLS templates.
The `Profiles and Mailpit` section supports only the local `dev` and `test`
profiles.

This monorepo contains independently buildable and deployable microservices for
an order platform. The current local dev/test setup contains 15 application
services (including the API Gateway) and 14 independent PostgreSQL databases.
The API Gateway has no database of its own.

## Publishing to GitHub

The repository is prepared for publishing its source code. Before the first
commit, run:

```bash
bash scripts/control-center/control-center.sh github-check
git status --short --ignored
```

Do not push the contents of `gitIgnoreOwn/`, local `.env` files, real
`*.env.sops` files, age/JWT keys, private certificates, kubeconfig files,
database backups, logs, tokens, runtime reports, `target/`, `.m2/` or `.idea/`
directories to GitHub.

The rules are defined in [`.gitignore`](<.gitignore>). The ignored directory
layout and privacy rules are described in
[`gitIgnoreOwn/README.md`](<gitIgnoreOwn/README.md>). A complete Polish guide
to the first commit, branches, pushes, Pull Requests, conflicts, and safe undo
operations is available in
[`docs/Git_Instruction.md`](<docs/Git_Instruction.md>).

The SOPS + age vault is stored outside the project by default. On Windows it is
stored in WSL, on Linux in `~/.config/mini-order-platform`, and on macOS in
`~/Library/Application Support/mini-order-platform`.

## Services

| Service | Directory | Port | Status |
| --- | --- | ---: | --- |
| API Gateway | `api-gateway` | 18080 | MVP |
| Product Service | `product-service` | 18081 | MVP |
| Order Service | `order-service` | 18082 | MVP |
| Inventory Service | `inventory-service` | 18083 | MVP |
| Payment Service | `payment-service` | 18084 | MVP |
| Notification Service | `notification-service` | 18086 | MVP |
| Customer Service | `customer-service` | 18087 | MVP |
| Delivery Service | `delivery-service` | 18088 | MVP |
| Promotion Service | `promotion-service` | 18089 | MVP |
| Wishlist Service | `wishlist-service` | 18090 | MVP |
| Review Service | `review-service` | 18091 | MVP |
| Recommendation Service | `recommendation-service` | 18092 | MVP |
| Chat Service | `chat-service` | 18093 | MVP |
| Returns Service | `returns-service` | 18094 | MVP |
| Billing Service | `billing-service` | 18095 | MVP |

Each service directory has its own `pom.xml`, application class, configuration
and tests. The root `pom.xml` is only a Maven aggregator and does not start an
application of its own. Every service except API Gateway owns a separate
PostgreSQL database; no service shares another service's tables.

## Service responsibilities and data ownership

Product Service has its own PostgreSQL database on host port `15433` mapped to
container port `5432`. It exposes the product catalogue at
`http://localhost:18081` and stores SKU, category, manufacturer, model,
technical parameters, price and the `active` status.

Order Service has its own PostgreSQL database on host port `15432`. When an
order is created, it obtains the product and price from Product Service and
then reserves the requested quantity through Inventory Service.

Inventory Service has its own PostgreSQL database on host port `15434`. It
handles stock levels, reservations, releasing reservations and confirming
reservations.

API Gateway has no database of its own. It is the common entry point at
`http://localhost:18080` and forwards the versioned public API paths
`/api/v1/products`, `/api/v1/orders`, `/api/v1/inventory`,
`/api/v1/payments`, `/api/v1/notifications`, `/api/v1/customers`,
`/api/v1/deliveries`, `/api/v1/promotions`, `/api/v1/wishlists`,
`/api/v1/reviews`, `/api/v1/recommendations`, `/api/v1/chat`,
`/api/v1/returns` and `/api/v1/invoices` to the appropriate services.
The unversioned `/api` paths remain available for local compatibility.

Customer Service has its own PostgreSQL database on host port `15437`. It
handles registration, login, password reset, brute-force protection, security
auditing and delivery-address storage. Checkout supports both guest purchases
and purchases made through an account.

Delivery Service has its own PostgreSQL database on host port `15438`. It
provides delivery methods, cost calculation and estimated delivery time.
Checkout obtains a delivery quote before creating the order, and Order Service
stores the selected method and cost in the order.

Promotion Service has its own PostgreSQL database on host port `15439` and runs
at `http://localhost:18089`. It stores the active campaign configuration,
calculates the daily rotation of new products and promotions, and records price
history. Promotion codes can be listed and generated from Control Center. The
frontend validates a code during checkout, Order Service applies the returned
discount to the single order, and the code's `redeemedCount` is increased only
after successful payment. The bestsellers section obtains its ranking of paid
products from Order Service.

Wishlist Service has its own PostgreSQL database on host port `15440` and runs
at `http://localhost:18090`. It stores product identifiers saved by customers.
The frontend provides a heart button on product cards and a `My favourites`
section in the account menu. In the `dev` and `test` profiles, the customer ID
is passed from the frontend session. Before using the `prod` profile, resource
ownership must be enforced based on JWT claims.

Review Service has its own PostgreSQL database on host port `15441` and runs at
`http://localhost:18091`. It verifies that a customer bought the product in a
paid order, saves the review with `PENDING` status and exposes it publicly only
after moderation.

Recommendation Service has its own PostgreSQL database on host port `15442` and
runs at `http://localhost:18092`. It reads the active catalogue from Product
Service and, for a logged-in customer, reads only paid order history from Order
Service. The frontend shows similar products on the product details page. Its
database stores a technical query log and does not copy catalogue or payment
data.

The frontend also provides a product comparison feature. A maximum of four
products is stored locally in the browser, and the comparison table displays
Product Service data, including technical parameters, price and availability.
This feature does not require an additional database or microservice.

Payment Service has its own PostgreSQL database on host port `15435`. It accepts
payments for orders in `CONFIRMED` status, updates the order to `PAID`, supports
controlled payment-decline simulation and stores notification events in a
durable outbox.

Notification Service has its own PostgreSQL database on host port `15436`. It
stores order and payment notifications. In the `dev` and `test` profiles,
messages are sent through the local Mailpit SMTP server; `simulateFailure` is
still available for controlled failure tests.

Chat Service has its own PostgreSQL database on host port `15443` and runs at
`http://localhost:18093`. It stores customer support messages and operator
replies. The frontend polls the current messages to provide a lightweight live
chat. Control Center can list messages and send a reply; replies are delivered
through Notification Service and appear in Mailpit in the local profiles.

Returns Service has its own PostgreSQL database on host port `15444` and runs
at `http://localhost:18094`. It creates return or complaint requests, exposes a
customer's requests, and supports status changes such as review or resolution.
The operational list and status changes are available from Control Center.

Billing Service has its own PostgreSQL database on host port `15445` and runs
at `http://localhost:18095`. It issues one demonstration VAT invoice per paid
order, stores buyer and company invoice data, calculates net/VAT/gross values,
and generates a branded PDF. The invoice stores immutable snapshots of the
purchased products and presents their names, quantities, unit prices and line
totals, together with delivery cost and discount when applicable. A mixed cart
remains one order and therefore has one invoice, not one invoice per product or
per service. Control Center can show the invoice and save its PDF under the
ignored runtime directory.

Redis Event Broker runs on host port `16379`, mapped to container port `6379`.
Payment Service publishes to the `payment-events` stream, while Notification
Service consumes events through the `notification-service` consumer group.

Endpoint and service-startup details are available in
[order-service/README.md](<order-service/README.md>).

## Profiles and Mailpit

The `dev` and `test` profiles use Mailpit. SMTP is available locally at
`127.0.0.1:11025`, and the message UI is available at
`http://127.0.0.1:18025`.

The local SOPS vault must contain the following database secrets for each
profile: `PRODUCT_DB_PASSWORD`, `ORDER_DB_PASSWORD`,
`INVENTORY_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`,
`CUSTOMER_DB_PASSWORD`, `DELIVERY_DB_PASSWORD`, `PROMOTION_DB_PASSWORD`,
`WISHLIST_DB_PASSWORD`, `REVIEW_DB_PASSWORD`, `RECOMMENDATION_DB_PASSWORD`,
`CHAT_DB_PASSWORD`, `RETURNS_DB_PASSWORD` and `BILLING_DB_PASSWORD`.
`GRAFANA_ADMIN_PASSWORD` is required for monitoring. Customer authentication
requires either the legacy `CUSTOMER_JWT_SECRET` or the complete RSA/JWKS set
(`CUSTOMER_JWT_ACTIVE_KID`, `CUSTOMER_JWT_PRIVATE_KEY_BASE64` and
`CUSTOMER_JWT_PUBLIC_KEYS_JSON`). Use Control Center's
`secrets-init`, `secrets-setup dev` or `secrets-setup test`, `secrets-edit` and
`secrets-validate` commands, or use the browser's `Create DEV/TEST secrets`
forms under `Operations → Profiles and secrets`. The browser wizard requires
the fourteen database passwords, a Base64 JWT secret of at least 32 decoded
bytes, and the Grafana password; it applies local Mailpit defaults, encrypts
the profile with SOPS + age, and does not start services. Do not put real
values in the repository's example `.env` files.

The technical Spring profile retains the `prod-vault` name for compatibility,
while the production templates target OpenBao. OpenBao has not been deployed in
the current local environment. Production startup from Control Center remains
blocked until the production secret-management integration is completed.

## Starting the project with Control Center

Control Center is the main interface for operating the project. The Bash entry
point detects the environment (`wsl`, Linux or macOS), starts a local dark
operator dashboard, and loads the appropriate platform adapter:

```bash
bash ./scripts/start.sh
```

The dashboard is bound to `http://127.0.0.1:18100` and includes service health,
112 allow-listed operations, customer chat replies, tests, background job logs,
and an embedded WSL terminal. Start the original terminal menu with:

```bash
bash ./scripts/start.sh --cli
```

Check the detected platform with:

```bash
bash scripts/control-center/control-center.sh platform-info
```

The adapter can be selected explicitly for diagnostics, for example with
`MINI_ORDER_PLATFORM=macos` or `MINI_ORDER_PLATFORM=linux`. Normal startup uses
automatic detection.

Alternatively, open a separate WSL window from PowerShell:

```powershell
.\scripts\start.ps1
```

Before the first startup, check the repository and environment:

```bash
bash scripts/control-center/control-center.sh github-check
bash scripts/control-center/control-center.sh environment-check
# optional: show the missing-installation plan; installation requires ACCEPT
bash scripts/control-center/control-center.sh environment-install
```

The complete startup sequence for the `dev` profile is:

```bash
bash scripts/control-center/control-center.sh profile-set dev
bash scripts/control-center/control-center.sh infra-up
bash scripts/control-center/control-center.sh broker-up
bash scripts/control-center/control-center.sh mailpit-up
bash scripts/control-center/control-center.sh app-run-all
bash scripts/control-center/control-center.sh frontend-up
bash scripts/control-center/control-center.sh doctor
```

If secrets do not exist, first run `secrets-init` and `secrets-setup dev`, or use
the web dashboard action `Operations → Profiles and secrets → Create DEV
secrets`. For tests, use the matching `test` setup action and validate the test
vault before starting it. The browser wizard encrypts the profile and does not
start services automatically.
`infra-up` starts all 14 databases, while `app-run-all` starts all 15
application services, including Chat, Returns and Billing. Control Center
automatically loads SOPS + age secrets and sets `SPRING_PROFILES_ACTIVE`; do
not start the infrastructure with an incomplete manual `docker compose up`
command.

Control Center also handles status checks, logs, tests, database backups, JWT,
Mailpit, monitoring, DLQ, E2E tests, OpenAPI, ZAP/DAST and diagnostics. Display
the complete command list with:

```bash
bash scripts/control-center/control-center.sh --list
```

Run the Maven tests for the entire monorepo with:

```bash
./mvnw -B test
./mvnw -B -Pintegration verify
```

Browser regression tests use Playwright. They require Node.js 20+ (Node.js 22
LTS is recommended). Control Center can install the project dependencies and
Chromium, run mocked UI regression without the backend, run a real full-system
checkout, and serve the HTML report:

```bash
bash scripts/control-center/control-center.sh ui-test-status
bash scripts/control-center/control-center.sh ui-test-install
bash scripts/control-center/control-center.sh ui-test-smoke
bash scripts/control-center/control-center.sh ui-test
bash scripts/control-center/control-center.sh ui-test-system
bash scripts/control-center/control-center.sh ui-test-browsers
bash scripts/control-center/control-center.sh ui-test-headed
bash scripts/control-center/control-center.sh ui-test-debug
bash scripts/control-center/control-center.sh ui-test-report
```

The interactive path is `Projects and applications → Testing and API quality →
11–19`. Test artifacts are written under `gitIgnoreOwn/runtime/playwright/`.
The complete test matrix and prerequisites are documented in
[docs/General_Instruction_EN.md](<docs/General_Instruction_EN.md#playwright-ui-tests>).

Run a full diagnostic of the active environment with:

```bash
bash scripts/control-center/control-center.sh doctor
```

Host ports are intentionally different from the occupied ports `5432`, `5400`,
`5300` and `6379`. Redis uses `16379:6379`.

Infrastructure ports are bound only to `127.0.0.1`, so PostgreSQL, Redis and
Mailpit are available locally and are not exposed on network interfaces.

Profiles, Mailpit and further instructions are described in
[docs/General_Instruction_EN.md](<docs/General_Instruction_EN.md>), the
[Control Center manual](<docs/Microservices_Central_Instruction_EN.md>), and
the local-only `docs/TODOList.md` file. `TODOList.md` is deliberately ignored
by Git and is not published to GitHub.

Profile secrets are described in [docs/Secrets.md](<docs/Secrets.md>). WSL uses
local SOPS + age, while production Vault preparation is documented in
[infra/vault/README.md](<infra/vault/README.md>).

Services can be started from IntelliJ using the wrapper described in
[docs/IntelliJ.md](<docs/IntelliJ.md>).

## Docker and Kubernetes deployments

Build local images for the microservices and frontend with:

```bash
bash scripts/control-center/control-center.sh images-build
```

Kubernetes manifests with rolling updates, Actuator probes, Services,
ConfigMaps and Ingress are located in [deploy/k8s](<deploy/k8s>). Deployment and
secret configuration are documented in
[docs/General_Instruction_EN.md](<docs/General_Instruction_EN.md>).

The GitHub Actions pipeline scans images with Trivy, signs them with Cosign via
GitHub OIDC and publishes them to GHCR. A manual deployment to a cluster
requires `KUBE_CONFIG_B64` and a previously created
`minitech-store-secrets` Kubernetes secret.

## Monitoring

Monitoring is started using the separate `docker-compose.monitoring.yaml` file.
Prometheus runs at `http://127.0.0.1:19090`, and Grafana runs at
`http://127.0.0.1:13000`. The images are `prom/prometheus:v3.5.0` and
`grafana/grafana:12.1.1`, respectively. All services expose Micrometer metrics
at `/actuator/prometheus`.

Notification Service has a durable DLQ in its database. After retries are
exhausted, inspect an entry with Control Center using `dlq-list` and retry it
with `dlq-replay <id>`.

## Frontend

The demonstrative MiniTech Store frontend is located in the
[frontend](<frontend>) directory. Start it with:

```bash
bash scripts/control-center/control-center.sh frontend-up
```

The frontend runs at `http://127.0.0.1:18070` and uses API Gateway for the
catalogue, account/JWT, cart and checkout flows. It also supports daily product
and promotion rotation, category-specific filters, price ranges, product
details, wishlist, comparison, recommendations, verified reviews, delivery
quotes and tracking, invoice selection, support chat, returns and complaints.
In `dev` and `test`, payment and e-mail delivery are simulated locally; Mailpit
is the place to inspect registration, order, payment, chat and invoice-related
messages.

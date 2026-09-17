#!/usr/bin/env bash
# Core constants, paths, colors, and helpers for Mini Order Platform Control Center

if [[ -s "${HOME:-}/.nvm/nvm.sh" ]]; then
  export NVM_DIR="${HOME:-}/.nvm"
  # shellcheck source=/dev/null
  \. "$NVM_DIR/nvm.sh" --no-use 2>/dev/null || true
  nvm use 22 >/dev/null 2>&1 || nvm use default >/dev/null 2>&1 || true
fi

SCRIPT_DIR="${SCRIPT_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
PROJECT_ROOT="${PROJECT_ROOT:-$(cd -- "$SCRIPT_DIR/../.." && pwd)}"
COMPOSE_FILE="$PROJECT_ROOT/compose.yaml"
APPS_COMPOSE_FILE="$PROJECT_ROOT/compose.apps.yaml"
MONITORING_COMPOSE_FILE="$PROJECT_ROOT/docker-compose.monitoring.yaml"
MAVEN_WRAPPER="$PROJECT_ROOT/mvnw"

DB_SERVICES=(product-db order-db inventory-db payment-db notification-db customer-db delivery-db promotion-db wishlist-db review-db recommendation-db chat-db returns-db billing-db)
BROKER_SERVICE="event-broker"
BROKER_PORT="16379"
PAYMENT_EVENT_STREAM="${PAYMENT_EVENT_STREAM:-payment-events}"
NOTIFICATION_CONSUMER_GROUP="${NOTIFICATION_CONSUMER_GROUP:-notification-service}"
MAILPIT_SERVICE="mailpit"
MAILPIT_SMTP_PORT="11025"
MAILPIT_WEB_PORT="18025"
PROMETHEUS_PORT="19090"
GRAFANA_PORT="13000"
OPENBAO_ADDR="${OPENBAO_ADDR:-https://127.0.0.1:18200}"
OPENBAO_CACERT="${OPENBAO_CACERT:-}"
TLS_CHECK_HOST="${TLS_CHECK_HOST:-minitech.example}"
TLS_CHECK_PORT="${TLS_CHECK_PORT:-443}"
TLS_CHECK_CACERT="${TLS_CHECK_CACERT:-}"
FRONTEND_PORT="18070"
FRONTEND_DIRECTORY="$PROJECT_ROOT/frontend"
PRIVATE_DIRECTORY="$PROJECT_ROOT/gitIgnoreOwn"
RUNTIME_DIRECTORY="$PRIVATE_DIRECTORY/runtime"
ACTIVE_PROFILE_FILE="$RUNTIME_DIRECTORY/active-profile"
LANGUAGE_FILE="$RUNTIME_DIRECTORY/language"
CONFIG_DIRECTORY="${MINI_ORDER_PLATFORM_CONFIG_HOME:-$(platform_config_directory 2>/dev/null || printf '%s/.config' "$HOME")}"
SECRET_ROOT_DIRECTORY="${MINI_ORDER_PLATFORM_SECRETS_DIR:-$CONFIG_DIRECTORY/mini-order-platform}"
SECRET_KEY_DIRECTORY="$SECRET_ROOT_DIRECTORY/keys"
SECRET_FILE_DIRECTORY="$SECRET_ROOT_DIRECTORY/secrets"
BACKUP_ROOT_DIRECTORY="${MINI_ORDER_PLATFORM_BACKUP_DIR:-$PRIVATE_DIRECTORY/backups}"
BACKUP_RETENTION_DAYS="${MINI_ORDER_PLATFORM_BACKUP_RETENTION_DAYS:-14}"
BACKUP_SCHEDULE_MARKER="# MINI_ORDER_PLATFORM_DB_BACKUP"

UI_LANGUAGE="${CONTROL_CENTER_LANGUAGE:-}"

if [[ -t 1 ]]; then
  C_BLUE=$'\033[1;34m'
  C_GREEN=$'\033[1;32m'
  C_RED=$'\033[1;31m'
  C_YELLOW=$'\033[1;33m'
  C_BOLD=$'\033[1m'
  C_DIM=$'\033[2m'
  C_RESET=$'\033[0m'
else
  C_BLUE=""
  C_GREEN=""
  C_RED=""
  C_YELLOW=""
  C_BOLD=""
  C_DIM=""
  C_RESET=""
fi

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '%sBrak wymaganej komendy: %s%s\n' "$C_RED" "$1" "$C_RESET" >&2
    return 1
  }
}


prepare_java_environment() {
  # IntelliJ/Windows can pass a Windows JAVA_HOME into WSL. Maven running in
  # WSL cannot use that path, so let it resolve the Linux JDK from PATH.
  if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
    printf '%sNieprawidlowe JAVA_HOME dla WSL — uzywam JDK z PATH.%s\n' \
      "$C_DIM" "$C_RESET" >&2
    unset JAVA_HOME
  fi
  require_command java || {
    printf '%s%s%s\n' "$C_RED" "$(platform_java_install_hint)" "$C_RESET" >&2
    return 1
  }
  require_command javac || {
    printf '%sBrak javac. %s%s\n' "$C_RED" "$(platform_java_install_hint)" "$C_RESET" >&2
    return 1
  }
}


dotenv_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\\$}"
  value="${value//\`/\\\`}"
  printf '"%s"' "$value"
}


dotenv_append() {
  local variable="$1"
  local value="$2"
  local file="$3"
  printf '%s=%s\n' "$variable" "$(dotenv_quote "$value")" >> "$file"
}


active_profile() {
  if [[ -f "$ACTIVE_PROFILE_FILE" ]]; then
    tr -d '\r\n' < "$ACTIVE_PROFILE_FILE"
  else
    printf 'dev'
  fi
}


load_language() {
  local candidate="${UI_LANGUAGE:-}"
  if [[ -z "$candidate" && -f "$LANGUAGE_FILE" ]]; then
    candidate="$(tr -d '\r\n' < "$LANGUAGE_FILE")"
  fi
  case "$(platform_lowercase "$candidate")" in
    en|english) UI_LANGUAGE="en" ;;
    pl|polish|polski|"") UI_LANGUAGE="pl" ;;
    *) UI_LANGUAGE="pl" ;;
  esac
}


language_name() {
  case "${1:-$UI_LANGUAGE}" in
    en) printf 'English' ;;
    *) printf 'Polski' ;;
  esac
}


language_set() {
  local requested="${1:-}"
  case "$(platform_lowercase "$requested")" in
    1|pl|polish|polski) UI_LANGUAGE="pl" ;;
    2|en|english) UI_LANGUAGE="en" ;;
    *)
      if [[ "$UI_LANGUAGE" == "en" ]]; then
        printf '%sLanguage must be Polish (pl) or English (en).%s\n' "$C_RED" "$C_RESET" >&2
      else
        printf '%sJezyk musi byc ustawiony na polski (pl) albo angielski (en).%s\n' "$C_RED" "$C_RESET" >&2
      fi
      return 2
      ;;
  esac
  mkdir -p "$RUNTIME_DIRECTORY"
  printf '%s\n' "$UI_LANGUAGE" > "$LANGUAGE_FILE"
  if [[ "$UI_LANGUAGE" == "en" ]]; then
    printf 'Control Center language set to English.\n'
  else
    printf 'Jezyk centrali ustawiony na polski.\n'
  fi
}


language_show() {
  if [[ "$UI_LANGUAGE" == "en" ]]; then
    printf 'Control Center language: English (en)\n'
  else
    printf 'Jezyk centrali: Polski (pl)\n'
  fi
  printf 'Language file: %s\n' "$LANGUAGE_FILE"
}


cc_text() {
  local polish="$1"
  local english="${2:-$polish}"
  if [[ "$UI_LANGUAGE" == "en" ]]; then
    printf '%s' "$english"
  else
    printf '%s' "$polish"
  fi
}


choice_prompt() {
  printf '%s: ' "$(cc_text 'Wybor' 'Choice')"
}


invalid_choice() {
  printf '%s\n' "$(cc_text 'Nieprawidlowy wybor.' 'Invalid choice.')"
}


prod_status() {
  printf '%sProfil prod: NIEDOSTĘPNY lokalnie.%s\n' "$C_RED" "$C_RESET"
  printf 'Dostępne profile uruchomieniowe: dev, test.\n'
  printf 'Konfiguracja prod pozostaje w szablonach Kubernetes/OpenBao/TLS do późniejszego wdrożenia.\n'
}


ensure_local_profile() {
  case "$(active_profile)" in
    dev|test) return 0 ;;
    prod)
      prod_status >&2
      return 1
      ;;
    *)
      printf '%sNieznany aktywny profil. Ustaw dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
      ;;
  esac
}


profile_show() {
  printf 'Aktywny profil: %s\n' "$(active_profile)"
  printf 'Plik profilu:   %s\n' "$ACTIVE_PROFILE_FILE"
  prod_status
}


profile_set() {
  local profile="${1:-}"
  case "$profile" in
    dev|test) ;;
    prod)
      prod_status >&2
      return 1
      ;;
    *) printf '%sProfil musi byc jednym z: dev albo test.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;;
  esac
  mkdir -p "$RUNTIME_DIRECTORY"
  printf '%s\n' "$profile" > "$ACTIVE_PROFILE_FILE"
  printf 'Ustawiono profil: %s\n' "$profile"
  if ! [[ -f "$(secret_encrypted_file "$profile")" ]]; then
    printf '%sBrak zaszyfrowanych sekretow dla profilu %s. Uruchom: secrets-init oraz secrets-edit %s.%s\n' \
      "$C_RED" "$profile" "$profile" "$C_RESET"
  fi
}


json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}


app_project() {
  case "$1" in
    gateway|api-gateway) printf 'api-gateway' ;;
    product|product-service) printf 'product-service' ;;
    order|order-service) printf 'order-service' ;;
    inventory|inventory-service) printf 'inventory-service' ;;
    payment|payment-service) printf 'payment-service' ;;
    notification|notification-service) printf 'notification-service' ;;
    customer|customer-service) printf 'customer-service' ;;
    delivery|delivery-service) printf 'delivery-service' ;;
    promotion|promotion-service) printf 'promotion-service' ;;
    wishlist|wishlist-service) printf 'wishlist-service' ;;
    review|review-service) printf 'review-service' ;;
    recommendation|recommendation-service) printf 'recommendation-service' ;;
    chat|chat-service) printf 'chat-service' ;;
    returns|returns-service) printf 'returns-service' ;;
    billing|billing-service) printf 'billing-service' ;;
    *) return 1 ;;
  esac
}


app_port() {
  case "$1" in
    gateway|api-gateway) printf '18080' ;;
    product|product-service) printf '18081' ;;
    order|order-service) printf '18082' ;;
    inventory|inventory-service) printf '18083' ;;
    payment|payment-service) printf '18084' ;;
    notification|notification-service) printf '18086' ;;
    customer|customer-service) printf '18087' ;;
    delivery|delivery-service) printf '18088' ;;
    promotion|promotion-service) printf '18089' ;;
    wishlist|wishlist-service) printf '18090' ;;
    review|review-service) printf '18091' ;;
    recommendation|recommendation-service) printf '18092' ;;
    chat|chat-service) printf '18093' ;;
    returns|returns-service) printf '18094' ;;
    billing|billing-service) printf '18095' ;;
    *) return 1 ;;
  esac
}


app_health_path() {
  case "$1" in
    gateway|api-gateway) printf '/api/products' ;;
    product|product-service) printf '/actuator/health' ;;
    order|order-service) printf '/actuator/health' ;;
    inventory|inventory-service) printf '/actuator/health' ;;
    payment|payment-service) printf '/actuator/health' ;;
    notification|notification-service) printf '/actuator/health' ;;
    customer|customer-service) printf '/actuator/health' ;;
    delivery|delivery-service) printf '/actuator/health' ;;
    promotion|promotion-service) printf '/actuator/health' ;;
    wishlist|wishlist-service) printf '/actuator/health' ;;
    review|review-service) printf '/actuator/health' ;;
    recommendation|recommendation-service) printf '/actuator/health' ;;
    chat|chat-service) printf '/actuator/health' ;;
    returns|returns-service) printf '/actuator/health' ;;
    billing|billing-service) printf '/actuator/health' ;;
    *) return 1 ;;
  esac
}


app_db_password_variable() {
  case "$1" in
    product|product-service) printf 'PRODUCT_DB_PASSWORD' ;;
    order|order-service) printf 'ORDER_DB_PASSWORD' ;;
    inventory|inventory-service) printf 'INVENTORY_DB_PASSWORD' ;;
    payment|payment-service) printf 'PAYMENT_DB_PASSWORD' ;;
    notification|notification-service) printf 'NOTIFICATION_DB_PASSWORD' ;;
    customer|customer-service) printf 'CUSTOMER_DB_PASSWORD' ;;
    delivery|delivery-service) printf 'DELIVERY_DB_PASSWORD' ;;
    promotion|promotion-service) printf 'PROMOTION_DB_PASSWORD' ;;
    wishlist|wishlist-service) printf 'WISHLIST_DB_PASSWORD' ;;
    review|review-service) printf 'REVIEW_DB_PASSWORD' ;;
    recommendation|recommendation-service) printf 'RECOMMENDATION_DB_PASSWORD' ;;
    chat|chat-service) printf 'CHAT_DB_PASSWORD' ;;
    returns|returns-service) printf 'RETURNS_DB_PASSWORD' ;;
    billing|billing-service) printf 'BILLING_DB_PASSWORD' ;;
    gateway|api-gateway) return 0 ;;
    *) return 1 ;;
  esac
}


db_service() {
  case "$1" in
    product|product-service|product-db) printf 'product-db' ;;
    order|order-service|order-db) printf 'order-db' ;;
    inventory|inventory-service|inventory-db) printf 'inventory-db' ;;
    payment|payment-service|payment-db) printf 'payment-db' ;;
    notification|notification-service|notification-db) printf 'notification-db' ;;
    customer|customer-service|customer-db) printf 'customer-db' ;;
    delivery|delivery-service|delivery-db) printf 'delivery-db' ;;
    promotion|promotion-service|promotion-db) printf 'promotion-db' ;;
    wishlist|wishlist-service|wishlist-db) printf 'wishlist-db' ;;
    review|review-service|review-db) printf 'review-db' ;;
    recommendation|recommendation-service|recommendation-db) printf 'recommendation-db' ;;
    chat|chat-service|chat-db) printf 'chat-db' ;;
    returns|returns-service|returns-db) printf 'returns-db' ;;
    billing|billing-service|billing-db) printf 'billing-db' ;;
    *) return 1 ;;
  esac
}


db_port() {
  case "$1" in
    product|product-service|product-db) printf '15433' ;;
    order|order-service|order-db) printf '15432' ;;
    inventory|inventory-service|inventory-db) printf '15434' ;;
    payment|payment-service|payment-db) printf '15435' ;;
    notification|notification-service|notification-db) printf '15436' ;;
    customer|customer-service|customer-db) printf '15437' ;;
    delivery|delivery-service|delivery-db) printf '15438' ;;
    promotion|promotion-service|promotion-db) printf '15439' ;;
    wishlist|wishlist-service|wishlist-db) printf '15440' ;;
    review|review-service|review-db) printf '15441' ;;
    recommendation|recommendation-service|recommendation-db) printf '15442' ;;
    chat|chat-service|chat-db) printf '15443' ;;
    returns|returns-service|returns-db) printf '15444' ;;
    billing|billing-service|billing-db) printf '15445' ;;
    *) return 1 ;;
  esac
}


db_name() {
  case "$1" in
    product|product-service|product-db) printf 'product_db' ;;
    order|order-service|order-db) printf 'order_db' ;;
    inventory|inventory-service|inventory-db) printf 'inventory_db' ;;
    payment|payment-service|payment-db) printf 'payment_db' ;;
    notification|notification-service|notification-db) printf 'notification_db' ;;
    customer|customer-service|customer-db) printf 'customer_db' ;;
    delivery|delivery-service|delivery-db) printf 'delivery_db' ;;
    promotion|promotion-service|promotion-db) printf 'promotion_db' ;;
    wishlist|wishlist-service|wishlist-db) printf 'wishlist_db' ;;
    review|review-service|review-db) printf 'review_db' ;;
    recommendation|recommendation-service|recommendation-db) printf 'recommendation_db' ;;
    chat|chat-service|chat-db) printf 'chat_db' ;;
    returns|returns-service|returns-db) printf 'returns_db' ;;
    billing|billing-service|billing-db) printf 'billing_db' ;;
    *) return 1 ;;
  esac
}


db_user() {
  case "$1" in
    product|product-service|product-db) printf 'product_app' ;;
    order|order-service|order-db) printf 'order_app' ;;
    inventory|inventory-service|inventory-db) printf 'inventory_app' ;;
    payment|payment-service|payment-db) printf 'payment_app' ;;
    notification|notification-service|notification-db) printf 'notification_app' ;;
    customer|customer-service|customer-db) printf 'customer_app' ;;
    delivery|delivery-service|delivery-db) printf 'delivery_app' ;;
    promotion|promotion-service|promotion-db) printf 'promotion_app' ;;
    wishlist|wishlist-service|wishlist-db) printf 'wishlist_app' ;;
    review|review-service|review-db) printf 'review_app' ;;
    recommendation|recommendation-service|recommendation-db) printf 'recommendation_app' ;;
    chat|chat-service|chat-db) printf 'chat_app' ;;
    returns|returns-service|returns-db) printf 'returns_app' ;;
    billing|billing-service|billing-db) printf 'billing_app' ;;
    *) return 1 ;;
  esac
}


app_aliases() {
  # Start dependencies before services that call them during readiness checks.
  printf '%s\n' promotion delivery product inventory order payment notification customer wishlist review recommendation chat returns billing gateway
}



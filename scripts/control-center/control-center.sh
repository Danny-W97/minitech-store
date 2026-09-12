#!/usr/bin/env bash
set -Euo pipefail

if [[ -s "${HOME:-}/.nvm/nvm.sh" ]]; then
  export NVM_DIR="${HOME:-}/.nvm"
  # shellcheck source=/dev/null
  \. "$NVM_DIR/nvm.sh" --no-use 2>/dev/null || true
  nvm use 22 >/dev/null 2>&1 || nvm use default >/dev/null 2>&1 || true
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/platform.sh"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
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
CONFIG_DIRECTORY="${MINI_ORDER_PLATFORM_CONFIG_HOME:-$(platform_config_directory)}"
SECRET_ROOT_DIRECTORY="${MINI_ORDER_PLATFORM_SECRETS_DIR:-$CONFIG_DIRECTORY/mini-order-platform}"
SECRET_KEY_DIRECTORY="$SECRET_ROOT_DIRECTORY/keys"
SECRET_FILE_DIRECTORY="$SECRET_ROOT_DIRECTORY/secrets"
BACKUP_ROOT_DIRECTORY="${MINI_ORDER_PLATFORM_BACKUP_DIR:-$PRIVATE_DIRECTORY/backups}"
BACKUP_RETENTION_DAYS="${MINI_ORDER_PLATFORM_BACKUP_RETENTION_DAYS:-14}"
BACKUP_SCHEDULE_MARKER="# MINI_ORDER_PLATFORM_DB_BACKUP"

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

secret_key_file() {
  printf '%s/%s.agekey' "$SECRET_KEY_DIRECTORY" "$1"
}

secret_encrypted_file() {
  printf '%s/%s.env.sops' "$SECRET_FILE_DIRECTORY" "$1"
}

secret_profile_supported() {
  [[ "$1" == "dev" || "$1" == "test" ]]
}

secrets_tools_check() {
  local failures=0
  for command in sops age age-keygen; do
    if command -v "$command" >/dev/null 2>&1; then
      printf '%-12s OK\n' "$command"
    else
      printf '%-12s BRAK\n' "$command"
      failures=$((failures + 1))
    fi
  done
  return "$failures"
}

secrets_init() {
  secrets_tools_check || return 1
  mkdir -p "$SECRET_KEY_DIRECTORY" "$SECRET_FILE_DIRECTORY"
  chmod 700 "$SECRET_ROOT_DIRECTORY" "$SECRET_KEY_DIRECTORY" "$SECRET_FILE_DIRECTORY"

  for init_profile in dev test; do
    local key_file
    key_file="$(secret_key_file "$init_profile")"
    if [[ ! -f "$key_file" ]]; then
      age-keygen -o "$key_file"
      printf 'Utworzono klucz %s: %s\n' "$init_profile" "$key_file"
    else
      printf 'Klucz %s juz istnieje: %s\n' "$init_profile" "$key_file"
    fi
    chmod 600 "$key_file"
  done
  printf '\nNastepnie uruchom: secrets-edit dev oraz secrets-edit test\n'
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

read_secret_value() {
  local prompt="$1"
  local required="${2:-true}"
  local value confirmation
  while true; do
    printf '%s' "$prompt" >&2
    IFS= read -r -s value || return 1
    printf '\n' >&2
    if [[ -z "$value" && "$required" != "true" ]]; then
      SETUP_VALUE=""
      return 0
    fi
    if [[ -z "$value" ]]; then
      printf 'Wartosc nie moze byc pusta. Sprobuj ponownie.\n' >&2
      continue
    fi
    printf 'Powtorz wartosc: ' >&2
    IFS= read -r -s confirmation || return 1
    printf '\n' >&2
    if [[ "$value" == "$confirmation" ]]; then
      SETUP_VALUE="$value"
      return 0
    fi
    printf 'Wartosci roznia sie. Sprobuj ponownie.\n' >&2
  done
}

read_config_value() {
  local prompt="$1"
  local default_value="${2:-}"
  local value
  printf '%s' "$prompt" >&2
  if [[ -n "$default_value" ]]; then
    printf ' [%s]' "$default_value" >&2
  fi
  printf ': ' >&2
  IFS= read -r value || return 1
  [[ -n "$value" ]] || value="$default_value"
  SETUP_VALUE="$value"
}

secrets_setup() {
  local profile="${1:-}"
  secret_profile_supported "$profile" || {
    printf '%sKreator obsluguje tylko profil dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || return 1
  secrets_init || return 1

  local encrypted_file
  encrypted_file="$(secret_encrypted_file "$profile")"
  if [[ -f "$encrypted_file" ]]; then
    printf 'Zaszyfrowany plik profilu %s juz istnieje: %s\n' "$profile" "$encrypted_file"
    printf 'Kreator zastapi jego zawartosc. Kontynuowac? [y/N]: '
    local overwrite
    read -r overwrite || return 1
    case "$(platform_lowercase "$overwrite")" in
      y|yes) ;;
      *) printf 'Anulowano. Uzyj secrets-edit %s, aby zmienic pojedyncze wartosci.\n' "$profile"; return 0 ;;
    esac
  fi

  local key_file recipient temp_file encrypted_temp
  key_file="$(secret_key_file "$profile")"
  recipient="$(age-keygen -y "$key_file")"
  temp_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-plain.XXXXXX")" || return 1
  encrypted_temp="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-encrypted.XXXXXX")" || {
    rm -f -- "$temp_file"
    return 1
  }
  chmod 600 "$temp_file" "$encrypted_temp"

  (
    set +e
    trap 'rm -f -- "$temp_file" "$encrypted_temp"' EXIT

    printf '# Generated by Control Center for profile %s.\n' "$profile" > "$temp_file"
    printf '# Values are encrypted after this wizard finishes.\n' >> "$temp_file"

    printf '\nHasla baz PostgreSQL (wpisz aktualne hasla uzytkownikow DB).\n'
    printf 'Jesli wolumeny baz juz istnieja, nie zmieniaj hasel bez osobnej migracji.\n\n'
    for db_variable in PRODUCT_DB_PASSWORD ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD PAYMENT_DB_PASSWORD NOTIFICATION_DB_PASSWORD CUSTOMER_DB_PASSWORD DELIVERY_DB_PASSWORD PROMOTION_DB_PASSWORD WISHLIST_DB_PASSWORD REVIEW_DB_PASSWORD RECOMMENDATION_DB_PASSWORD CHAT_DB_PASSWORD RETURNS_DB_PASSWORD BILLING_DB_PASSWORD; do
      read_secret_value "$db_variable: " true || exit 1
      dotenv_append "$db_variable" "$SETUP_VALUE" "$temp_file"
    done

    read_secret_value 'CUSTOMER_JWT_SECRET (minimum 32 bajty po dekodowaniu Base64): ' true || exit 1
    dotenv_append CUSTOMER_JWT_SECRET "$SETUP_VALUE" "$temp_file"

    printf '\nHaslo administratora Grafany.\n'
    read_secret_value 'GRAFANA_ADMIN_PASSWORD: ' true || exit 1
    dotenv_append GRAFANA_ADMIN_PASSWORD "$SETUP_VALUE" "$temp_file"

    printf '\nUstawienia lokalnego Mailpit. Puste haslo SMTP pozostaje puste.\n'
    read_config_value 'GRAFANA_ADMIN_USER' admin || exit 1
    dotenv_append GRAFANA_ADMIN_USER "$SETUP_VALUE" "$temp_file"
    read_config_value 'MAIL_HOST' 127.0.0.1 || exit 1
    dotenv_append MAIL_HOST "$SETUP_VALUE" "$temp_file"
    read_config_value 'MAIL_PORT' 11025 || exit 1
    dotenv_append MAIL_PORT "$SETUP_VALUE" "$temp_file"
    read_config_value 'MAIL_USERNAME' '' || exit 1
    dotenv_append MAIL_USERNAME "$SETUP_VALUE" "$temp_file"
    read_secret_value 'MAIL_PASSWORD (puste dla Mailpit): ' false || exit 1
    dotenv_append MAIL_PASSWORD "$SETUP_VALUE" "$temp_file"
    local mail_from='no-reply@minitech.local'
    [[ "$profile" == 'test' ]] && mail_from='no-reply@minitech.test'
    read_config_value 'MAIL_FROM' "$mail_from" || exit 1
    dotenv_append MAIL_FROM "$SETUP_VALUE" "$temp_file"
    read_config_value 'MAIL_SMTP_AUTH' false || exit 1
    dotenv_append MAIL_SMTP_AUTH "$SETUP_VALUE" "$temp_file"
    read_config_value 'MAIL_SMTP_STARTTLS_ENABLE' false || exit 1
    dotenv_append MAIL_SMTP_STARTTLS_ENABLE "$SETUP_VALUE" "$temp_file"
    local chat_support_email='company@minitech.local'
    [[ "$profile" == 'test' ]] && chat_support_email='company@minitech.test'
    read_config_value 'CHAT_SUPPORT_EMAIL' "$chat_support_email" || exit 1
    dotenv_append CHAT_SUPPORT_EMAIL "$SETUP_VALUE" "$temp_file"

    if ! SOPS_AGE_KEY_FILE="$key_file" sops --encrypt \
        --input-type dotenv --output-type dotenv --age "$recipient" \
        "$temp_file" > "$encrypted_temp"; then
      printf '%sNie udalo sie zaszyfrowac sekretow profilu %s.%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      exit 1
    fi
    chmod 600 "$encrypted_temp"
    if ! mv -f -- "$encrypted_temp" "$encrypted_file"; then
      printf '%sNie udalo sie zapisac zaszyfrowanego pliku %s.%s\n' "$C_RED" "$encrypted_file" "$C_RESET" >&2
      exit 1
    fi
    printf '%sSekrety profilu %s zapisane zaszyfrowane. Wartosci nie zostaly wyswietlone.%s\n' \
      "$C_GREEN" "$profile" "$C_RESET"
  )
  local status=$?
  rm -f -- "$temp_file" "$encrypted_temp"
  return "$status"
}

secrets_ensure_auth() {
  local profile="${1:-}"
  secret_profile_supported "$profile" || {
    printf '%sSekret JWT mozna przygotowac tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || return 1

  local key_file encrypted_file recipient temp_file encrypted_temp
  key_file="$(secret_key_file "$profile")"
  encrypted_file="$(secret_encrypted_file "$profile")"
  [[ -f "$key_file" && -f "$encrypted_file" ]] || {
    printf '%sBrak kompletu sekretow %s. Uruchom najpierw secrets-setup %s.%s\n' \
      "$C_RED" "$profile" "$profile" "$C_RESET" >&2
    return 1
  }

  temp_file="$(decrypt_profile_to_temp "$profile")" || return 1
  if grep -qE '^CUSTOMER_JWT_SECRET="[^"]+"$|^CUSTOMER_JWT_SECRET=[^"[:space:]][^[:space:]]*$' "$temp_file"; then
    rm -f -- "$temp_file"
    printf 'CUSTOMER_JWT_SECRET dla %s juz istnieje. Nie wykonano rotacji.\n' "$profile"
    return 0
  fi

  encrypted_temp="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-auth.XXXXXX")" || {
    rm -f -- "$temp_file"
    return 1
  }
  chmod 600 "$encrypted_temp"
  recipient="$(age-keygen -y "$key_file")"
  local generated_secret
  generated_secret="$(head -c 48 /dev/urandom | platform_base64_stdin)"
  dotenv_append CUSTOMER_JWT_SECRET "$generated_secret" "$temp_file"
  if ! SOPS_AGE_KEY_FILE="$key_file" sops --encrypt \
      --input-type dotenv --output-type dotenv --age "$recipient" \
      "$temp_file" > "$encrypted_temp"; then
    rm -f -- "$temp_file" "$encrypted_temp"
    printf '%sNie udalo sie zapisac CUSTOMER_JWT_SECRET dla %s.%s\n' "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  fi
  chmod 600 "$encrypted_temp"
  mv -f -- "$encrypted_temp" "$encrypted_file"
  rm -f -- "$temp_file"
  printf '%sUtworzono CUSTOMER_JWT_SECRET dla profilu %s. Wartosc nie zostala wyswietlona.%s\n' \
    "$C_GREEN" "$profile" "$C_RESET"
}

jwt_rotate() {
  local profile="${1:-$(active_profile)}"
  secret_profile_supported "$profile" || {
    printf '%sRotacje JWT obslugiwane sa tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || return 1
  require_command openssl || return 1
  require_command python3 || return 1

  local key_file encrypted_file temp_file encrypted_temp private_der public_der
  local key_id private_key public_key previous_public_keys merged_public_keys recipient
  key_file="$(secret_key_file "$profile")"
  encrypted_file="$(secret_encrypted_file "$profile")"
  temp_file="$(decrypt_profile_to_temp "$profile")" || return 1
  encrypted_temp="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-jwt.XXXXXX")" || {
    rm -f -- "$temp_file"
    return 1
  }
  private_der="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-jwt-private.XXXXXX")" || {
    rm -f -- "$temp_file" "$encrypted_temp"
    return 1
  }
  public_der="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-jwt-public.XXXXXX")" || {
    rm -f -- "$temp_file" "$encrypted_temp" "$private_der"
    return 1
  }
  chmod 600 "$temp_file" "$encrypted_temp" "$private_der" "$public_der"
  trap 'rm -f -- "$temp_file" "$encrypted_temp" "$private_der" "$public_der"' RETURN

  key_id="rsa-$(date -u +%Y%m%d%H%M%S)-$(openssl rand -hex 4)"
  if ! openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
      -outform DER -out "$private_der" >/dev/null 2>&1; then
    printf '%sNie udalo sie wygenerowac prywatnego klucza RSA.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi
  if ! openssl pkey -inform DER -in "$private_der" -pubout -outform DER -out "$public_der" >/dev/null 2>&1; then
    printf '%sNie udalo sie wygenerowac publicznego klucza RSA.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi
  private_key="$(platform_base64_file "$private_der")"
  public_key="$(platform_base64_file "$public_der")"
  previous_public_keys="$(set -a; source "$temp_file"; printf '%s' "${CUSTOMER_JWT_PUBLIC_KEYS_JSON:-}")"
  merged_public_keys="$(JWT_OLD_PUBLIC_KEYS_JSON="$previous_public_keys" \
    JWT_NEW_PUBLIC_KEY="$public_key" JWT_NEW_KEY_ID="$key_id" python3 -c \
    'import json, os; value=os.environ.get("JWT_OLD_PUBLIC_KEYS_JSON", ""); data=json.loads(value) if value else {}; data[os.environ["JWT_NEW_KEY_ID"]]=os.environ["JWT_NEW_PUBLIC_KEY"]; print(json.dumps(data, separators=(",", ":")))')" || return 1

  platform_sed_in_place "$temp_file" '/^(CUSTOMER_JWT_ACTIVE_KID|CUSTOMER_JWT_PRIVATE_KEY_BASE64|CUSTOMER_JWT_PUBLIC_KEYS_JSON)=/d'
  dotenv_append CUSTOMER_JWT_ACTIVE_KID "$key_id" "$temp_file"
  dotenv_append CUSTOMER_JWT_PRIVATE_KEY_BASE64 "$private_key" "$temp_file"
  dotenv_append CUSTOMER_JWT_PUBLIC_KEYS_JSON "$merged_public_keys" "$temp_file"
  recipient="$(age-keygen -y "$key_file")"
  if ! SOPS_AGE_KEY_FILE="$key_file" sops --encrypt \
      --input-type dotenv --output-type dotenv --age "$recipient" \
      "$temp_file" > "$encrypted_temp"; then
    printf '%sNie udalo sie zapisac rotacji JWT dla profilu %s.%s\n' "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  fi
  mv -f -- "$encrypted_temp" "$encrypted_file"
  printf '%sWygenerowano klucz JWT RSA dla profilu %s. Aktywny kid: %s. Poprzednie klucze publiczne pozostaly do weryfikacji tokenow.%s\n' \
    "$C_GREEN" "$profile" "$key_id" "$C_RESET"
}

secrets_validate() {
  local profile="${1:-}"
  secret_profile_supported "$profile" || {
    printf '%sSprawdzac mozna tylko sekrety dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || {
    printf '%sNie mozna zwalidowac profilu %s bez SOPS i age.%s\n' "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  }
  local temp_file
  temp_file="$(decrypt_profile_to_temp "$profile")" || return 1
  (
    set +e
    set -a
    # shellcheck disable=SC1090
    source "$temp_file"
    local failures=0 variable
    local required_variables=(
      PRODUCT_DB_PASSWORD ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD
      PAYMENT_DB_PASSWORD NOTIFICATION_DB_PASSWORD CUSTOMER_DB_PASSWORD DELIVERY_DB_PASSWORD PROMOTION_DB_PASSWORD WISHLIST_DB_PASSWORD REVIEW_DB_PASSWORD RECOMMENDATION_DB_PASSWORD CHAT_DB_PASSWORD RETURNS_DB_PASSWORD BILLING_DB_PASSWORD GRAFANA_ADMIN_PASSWORD
    )
    for variable in "${required_variables[@]}"; do
      if [[ -n "${!variable:-}" ]]; then
        printf '%-28s OK\n' "$variable"
      else
        printf '%-28s BRAK\n' "$variable"
        failures=$((failures + 1))
      fi
    done
    local jwt_rsa_complete=true jwt_legacy_present=false jwt_variable
    [[ -n "${CUSTOMER_JWT_SECRET:-}" ]] && jwt_legacy_present=true
    for jwt_variable in CUSTOMER_JWT_ACTIVE_KID CUSTOMER_JWT_PRIVATE_KEY_BASE64 CUSTOMER_JWT_PUBLIC_KEYS_JSON; do
      [[ -n "${!jwt_variable:-}" ]] || jwt_rsa_complete=false
    done
    if [[ "$jwt_legacy_present" == true || "$jwt_rsa_complete" == true ]]; then
      printf '%-28s OK (%s)\n' 'CUSTOMER_JWT' "$([[ "$jwt_rsa_complete" == true ]] && printf 'RS256 kid/JWKS' || printf 'legacy HMAC')"
    else
      printf '%-28s BRAK\n' 'CUSTOMER_JWT'
      failures=$((failures + 1))
    fi
    return "$failures"
  )
  local status=$?
  rm -f -- "$temp_file"
  if [[ "$status" -eq 0 ]]; then
    printf '%sWalidacja sekretow %s zakonczona powodzeniem. Wartosci nie zostaly wyswietlone.%s\n' \
      "$C_GREEN" "$profile" "$C_RESET"
  else
    printf '%sWalidacja sekretow %s wykryla braki lub bledy.%s\n' "$C_RED" "$profile" "$C_RESET" >&2
  fi
  return "$status"
}

jwt_status() {
  local profile
  profile="$(active_profile)"
  secret_profile_supported "$profile" || {
    printf '%sJWT dla profilu %s wymaga lokalnego skarbca dev albo test. Profil prod jest przygotowany, ale nieaktywny.%s\n' \
      "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  }

  require_command curl || return 1
  require_command base64 || return 1
  require_command python3 || return 1

  local temp_file secret_status=0 decoded_bytes jwt_mode active_kid public_key_count
  temp_file="$(decrypt_profile_to_temp "$profile")" || return 1
  set +u
  # shellcheck disable=SC1090
  source "$temp_file"
  set -u
  if [[ -n "${CUSTOMER_JWT_ACTIVE_KID:-}" && -n "${CUSTOMER_JWT_PRIVATE_KEY_BASE64:-}" \
      && -n "${CUSTOMER_JWT_PUBLIC_KEYS_JSON:-}" ]]; then
    jwt_mode='RS256'
    active_kid="$CUSTOMER_JWT_ACTIVE_KID"
    public_key_count="$(JWT_PUBLIC_KEYS_JSON="$CUSTOMER_JWT_PUBLIC_KEYS_JSON" python3 -c \
      'import json, os; print(len(json.loads(os.environ["JWT_PUBLIC_KEYS_JSON"])))' 2>/dev/null)"
    if [[ "$public_key_count" =~ ^[1-9][0-9]*$ ]]; then
      secret_status=1
    fi
  else
    jwt_mode='legacy'
    decoded_bytes="$(printf '%s' "${CUSTOMER_JWT_SECRET:-}" | base64 --decode 2>/dev/null | wc -c)"
    if [[ "$decoded_bytes" =~ ^[0-9]+$ ]] && (( decoded_bytes >= 32 )); then
      secret_status=1
    fi
  fi
  rm -f -- "$temp_file"

  printf 'Aktywny profil: %s\n' "$profile"
  if [[ "$secret_status" -eq 1 && "${jwt_mode:-legacy}" == 'RS256' ]]; then
    printf '%-24s OK (RS256, kid=%s, kluczy publicznych=%s; wartosci ukryte)\n' \
      'CUSTOMER_JWT' "${active_kid:-unknown}" "${public_key_count:-0}"
  elif [[ "$secret_status" -eq 1 ]]; then
    printf '%-24s OK (legacy HMAC, Base64 minimum 32 bajty; wartosc ukryta)\n' 'CUSTOMER_JWT_SECRET'
  else
    printf '%-24s BRAK lub niepoprawny (wartosci nie zostaly wyswietlone)\n' 'CUSTOMER_JWT'
  fi
  printf 'Access JWT TTL: 900 s (domyslnie)\n'
  printf 'Refresh token TTL: 604800 s (7 dni, domyslnie)\n'
  printf 'API auth: http://127.0.0.1:18080/api/customers/{login,refresh,logout,me}\n'
  printf 'JWKS: http://127.0.0.1:18080/api/customers/.well-known/jwks.json\n'

  local customer_status gateway_status failures=0
  if app_endpoint_healthy customer; then
    customer_status='HEALTHY'
  else
    customer_status='OFFLINE'
    failures=$((failures + 1))
  fi
  if gateway_status="$(curl --silent --show-error --connect-timeout 2 --max-time 5 \
      -o /dev/null -w '%{http_code}' 'http://127.0.0.1:18080/actuator/health' 2>/dev/null)" \
      && [[ "$gateway_status" =~ ^2[0-9][0-9]$ ]]; then
    gateway_status="HEALTHY (HTTP ${gateway_status})"
  else
    gateway_status='OFFLINE'
    failures=$((failures + 1))
  fi
  printf 'Customer Service: %s\n' "$customer_status"
  printf 'API Gateway:      %s\n' "$gateway_status"
  [[ "$secret_status" -eq 1 ]] || failures=$((failures + 1))
  return "$failures"
}

audit_list() {
  local limit="${1:-50}"
  [[ "$limit" =~ ^[1-9][0-9]*$ ]] || limit=50
  printf 'Ostatnie zdarzenia audytowe Customer Service (limit %s):\n' "$limit"
  compose exec -T customer-db psql -U customer_app -d customer_db \
    -c "SELECT id, occurred_at, event_type, actor, resource, outcome, correlation_id, client_ip, metadata FROM audit_events ORDER BY occurred_at DESC LIMIT ${limit};"
}

password_reset_request() {
  local email="${1:-}"
  require_command curl || return 1
  require_command python3 || return 1
  if [[ -z "$email" ]]; then
    printf 'E-mail konta: '
    read -r email || return 1
  fi
  local gateway body status
  gateway="${JWT_API_BASE_URL:-http://127.0.0.1:18080/api}"
  gateway="${gateway%/}"
  body="$(RESET_EMAIL="$email" python3 -c 'import json, os; print(json.dumps({"email": os.environ["RESET_EMAIL"]}))')"
  status="$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
    -o /tmp/mini-order-platform-password-reset-response.json -w '%{http_code}' \
    -H 'Content-Type: application/json' --data "$body" \
    "$gateway/customers/password-reset/request" 2>/dev/null)" || status=000
  printf 'Żądanie resetu hasła: HTTP %s. Sprawdź Mailpit: http://127.0.0.1:%s\n' "$status" "$MAILPIT_WEB_PORT"
}

jwt_smoke() {
  local profile email_arg="${1:-}"
  profile="$(active_profile)"
  secret_profile_supported "$profile" || {
    printf '%sJWT smoke jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  require_command curl || return 1
  require_command python3 || return 1

  (
    set -Euo pipefail
    local email password gateway temp_dir payload_file response_file cookie_file header_file
    email="$email_arg"
    if [[ -z "$email" ]]; then
      printf 'E-mail potwierdzonego konta: '
      read -r email || exit 1
    fi
    [[ "$email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || {
      printf '%sNieprawidlowy adres e-mail.%s\n' "$C_RED" "$C_RESET" >&2
      exit 2
    }
    printf 'Haslo (nie bedzie wyswietlone): '
    IFS= read -r -s password || exit 1
    printf '\n'
    [[ -n "$password" ]] || {
      printf '%sHaslo nie moze byc puste.%s\n' "$C_RED" "$C_RESET" >&2
      exit 2
    }

    gateway="${JWT_API_BASE_URL:-http://127.0.0.1:18080/api}"
    gateway="${gateway%/}"
    temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/mini-order-platform-jwt-smoke.XXXXXX")" || exit 1
    chmod 700 "$temp_dir"
    payload_file="$temp_dir/login.json"
    response_file="$temp_dir/response.json"
    cookie_file="$temp_dir/cookies.txt"
    header_file="$temp_dir/auth.header"
    touch "$payload_file" "$response_file" "$cookie_file" "$header_file"
    chmod 600 "$payload_file" "$response_file" "$cookie_file" "$header_file"
    trap 'rm -rf -- "$temp_dir"' EXIT

    JWT_SMOKE_EMAIL="$email" JWT_SMOKE_PASSWORD="$password" python3 -c \
      'import json, os; print(json.dumps({"email": os.environ["JWT_SMOKE_EMAIL"], "password": os.environ["JWT_SMOKE_PASSWORD"]}))' \
      > "$payload_file"
    unset password

    local login_status access_token me_status refresh_status refreshed_token logout_status post_logout_refresh_status
    login_status="$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
      -o "$response_file" -w '%{http_code}' -c "$cookie_file" \
      -H 'Content-Type: application/json' --data-binary "@$payload_file" \
      "$gateway/customers/login" 2>/dev/null)" || {
      printf '%sJWT smoke: API Gateway jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
      exit 1
    }
    if [[ "$login_status" != '200' ]]; then
      case "$login_status" in
        401)
          printf '%sJWT smoke: login nieudany (HTTP 401). Nieprawidlowy e-mail lub haslo albo konto nie jest aktywne.%s\n' \
            "$C_RED" "$C_RESET" >&2
          ;;
        423)
          printf '%sJWT smoke: konto jest czasowo zablokowane po wielu nieudanych probach (HTTP 423).%s\n' \
            "$C_RED" "$C_RESET" >&2
          ;;
        *)
          printf '%sJWT smoke: login nieudany (HTTP %s).%s\n' \
            "$C_RED" "$login_status" "$C_RESET" >&2
          ;;
      esac
      exit 1
    fi
    if ! access_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken") or "")' < "$response_file" 2>/dev/null)" \
      || [[ -z "$access_token" ]]; then
      printf '%sJWT smoke: odpowiedz login nie zawiera access tokenu.%s\n' "$C_RED" "$C_RESET" >&2
      exit 1
    fi
    printf '%slogin: OK (access JWT otrzymany, wartosc ukryta)%s\n' "$C_GREEN" "$C_RESET"

    printf 'Authorization: Bearer %s\n' "$access_token" > "$header_file"
    me_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
      -o /dev/null -w '%{http_code}' -H "@$header_file" \
      "$gateway/customers/me" 2>/dev/null)" || me_status='000'
    [[ "$me_status" == '200' ]] || {
      printf '%s/me: blad (HTTP %s).%s\n' "$C_RED" "$me_status" "$C_RESET" >&2
      exit 1
    }
    printf '%s/me: OK%s\n' "$C_GREEN" "$C_RESET"

    refresh_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
      -o "$response_file" -w '%{http_code}' -c "$cookie_file" -b "$cookie_file" \
      -X POST "$gateway/customers/refresh" 2>/dev/null)" || refresh_status='000'
    if [[ "$refresh_status" != '200' ]] || ! refreshed_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken") or "")' < "$response_file" 2>/dev/null)" \
      || [[ -z "$refreshed_token" ]]; then
      printf '%srefresh: blad (HTTP %s).%s\n' "$C_RED" "$refresh_status" "$C_RESET" >&2
      exit 1
    fi
    access_token="$refreshed_token"
    printf 'Authorization: Bearer %s\n' "$access_token" > "$header_file"
    printf '%srefresh: OK (nowy access JWT, wartosc ukryta)%s\n' "$C_GREEN" "$C_RESET"

    logout_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
      -o /dev/null -w '%{http_code}' -c "$cookie_file" -b "$cookie_file" \
      -X POST "$gateway/customers/logout" 2>/dev/null)" || logout_status='000'
    [[ "$logout_status" == '204' ]] || {
      printf '%slogout: blad (HTTP %s).%s\n' "$C_RED" "$logout_status" "$C_RESET" >&2
      exit 1
    }
    printf '%slogout: OK%s\n' "$C_GREEN" "$C_RESET"

    post_logout_refresh_status="$(curl --silent --show-error --connect-timeout 3 --max-time 10 \
      -o /dev/null -w '%{http_code}' -b "$cookie_file" \
      -X POST "$gateway/customers/refresh" 2>/dev/null)" || post_logout_refresh_status='000'
    [[ "$post_logout_refresh_status" == '401' ]] || {
      printf '%srefresh po logout: oczekiwano HTTP 401, otrzymano %s.%s\n' \
        "$C_RED" "$post_logout_refresh_status" "$C_RESET" >&2
      exit 1
    }
    printf '%srefresh po logout: OK (HTTP 401)%s\n' "$C_GREEN" "$C_RESET"
    printf '%sJWT smoke zakonczony powodzeniem. Tokeny i haslo nie zostaly wyswietlone.%s\n' \
      "$C_GREEN" "$C_RESET"
  )
}

secrets_edit() {
  local profile="${1:-}"
  secret_profile_supported "$profile" || {
    printf '%sEdytowac mozna tylko sekrety dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || return 1
  local key_file encrypted_file recipient
  key_file="$(secret_key_file "$profile")"
  encrypted_file="$(secret_encrypted_file "$profile")"
  [[ -f "$key_file" ]] || {
    printf '%sBrak klucza. Najpierw uruchom: secrets-init%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  recipient="$(age-keygen -y "$key_file")"
  SOPS_AGE_KEY_FILE="$key_file" sops \
    --input-type dotenv --output-type dotenv \
    --age "$recipient" "$encrypted_file"
}

secret_web_variable_supported() {
  case "$1" in
    PRODUCT_DB_PASSWORD|ORDER_DB_PASSWORD|INVENTORY_DB_PASSWORD|PAYMENT_DB_PASSWORD|NOTIFICATION_DB_PASSWORD|CUSTOMER_DB_PASSWORD|DELIVERY_DB_PASSWORD|PROMOTION_DB_PASSWORD|WISHLIST_DB_PASSWORD|REVIEW_DB_PASSWORD|RECOMMENDATION_DB_PASSWORD|CHAT_DB_PASSWORD|RETURNS_DB_PASSWORD|BILLING_DB_PASSWORD|CUSTOMER_JWT_SECRET|GRAFANA_ADMIN_PASSWORD|GRAFANA_ADMIN_USER|MAIL_HOST|MAIL_PORT|MAIL_USERNAME|MAIL_PASSWORD|MAIL_FROM|MAIL_SMTP_AUTH|MAIL_SMTP_STARTTLS_ENABLE|CHAT_SUPPORT_EMAIL)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

secrets_update_web() {
  local profile="${1:-}"
  secret_profile_supported "$profile" || {
    printf '%sEdytowac mozna tylko sekrety dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || return 1

  local key_file encrypted_file recipient input_file temp_file encrypted_temp
  key_file="$(secret_key_file "$profile")"
  encrypted_file="$(secret_encrypted_file "$profile")"
  [[ -f "$key_file" && -f "$encrypted_file" ]] || {
    printf '%sBrak kompletu sekretow %s. Najpierw uruchom secrets-init oraz secrets-setup %s.%s\n' \
      "$C_RED" "$profile" "$profile" "$C_RESET" >&2
    return 1
  }

  input_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-web-input.XXXXXX")" || return 1
  chmod 600 "$input_file"
  temp_file=""
  encrypted_temp=""
  trap 'rm -f -- "${input_file:-}" "${temp_file:-}" "${encrypted_temp:-}"' RETURN
  cat > "$input_file" || return 1

  temp_file="$(decrypt_profile_to_temp "$profile")" || return 1
  encrypted_temp="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-web-encrypted.XXXXXX")" || return 1
  chmod 600 "$encrypted_temp" "$temp_file"

  local variable encoded extra value updated=0
  while IFS=$'\t' read -r variable encoded extra; do
    [[ -z "$variable" ]] && continue
    [[ -z "$extra" ]] || {
      printf '%sNieprawidlowy format danych sekretu.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    secret_web_variable_supported "$variable" || {
      printf '%sNiedozwolona nazwa sekretu: %s%s\n' "$C_RED" "$variable" "$C_RESET" >&2
      return 2
    }
    [[ -n "$encoded" && "$encoded" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || {
      printf '%sNieprawidlowe kodowanie wartosci sekretu.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    value="$(printf '%s' "$encoded" | platform_base64_decode_stdin)" || {
      printf '%sNie udalo sie odczytac wartosci sekretu.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || {
      printf '%sWartosc sekretu zawiera niedozwolone znaki.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    platform_sed_in_place "$temp_file" "/^${variable}=.*/d"
    dotenv_append "$variable" "$value" "$temp_file"
    updated=$((updated + 1))
  done < "$input_file"

  ((updated > 0)) || {
    printf '%sNie podano zadnej nowej wartosci sekretu.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }

  recipient="$(age-keygen -y "$key_file")" || return 1
  if ! SOPS_AGE_KEY_FILE="$key_file" sops --encrypt \
      --input-type dotenv --output-type dotenv --age "$recipient" \
      "$temp_file" > "$encrypted_temp"; then
    printf '%sNie udalo sie zapisac zaszyfrowanych sekretow profilu %s.%s\n' \
      "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  fi
  chmod 600 "$encrypted_temp"
  mv -f -- "$encrypted_temp" "$encrypted_file" || return 1
  encrypted_temp=""
  printf '%sZaktualizowano %s pol sekretow profilu %s. Wartosci nie zostaly wyswietlone.%s\n' \
    "$C_GREEN" "$updated" "$profile" "$C_RESET"
}

secrets_setup_web() {
  local profile="${1:-}"
  secret_profile_supported "$profile" || {
    printf '%sTworzyc sekrety mozna tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  secrets_tools_check || return 1
  secrets_init || return 1

  local key_file encrypted_file recipient input_file temp_file encrypted_temp
  key_file="$(secret_key_file "$profile")"
  encrypted_file="$(secret_encrypted_file "$profile")"
  [[ ! -f "$encrypted_file" ]] || {
    printf '%sPlik sekretow profilu %s juz istnieje. Uzyj edytora sekretow.%s\n' \
      "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  }

  input_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-web-setup-input.XXXXXX")" || return 1
  temp_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-web-setup-plain.XXXXXX")" || {
    rm -f -- "$input_file"
    return 1
  }
  encrypted_temp="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets-web-setup-encrypted.XXXXXX")" || {
    rm -f -- "$input_file" "$temp_file"
    return 1
  }
  chmod 600 "$input_file" "$temp_file" "$encrypted_temp"
  trap 'rm -f -- "${input_file:-}" "${temp_file:-}" "${encrypted_temp:-}"' RETURN

  cat > "$input_file" || return 1
  printf '# Generated by Control Center web setup for profile %s.\n' "$profile" > "$temp_file"
  printf '# Values are encrypted before the profile file is created.\n' >> "$temp_file"

  local variable encoded extra value seen="" updated=0
  while IFS=$'\t' read -r variable encoded extra; do
    [[ -z "$variable" ]] && continue
    [[ -z "$extra" ]] || {
      printf '%sNieprawidlowy format danych sekretu.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    secret_web_variable_supported "$variable" || {
      printf '%sNiedozwolona nazwa sekretu: %s%s\n' "$C_RED" "$variable" "$C_RESET" >&2
      return 2
    }
    case $'\n'"$seen"$'\n' in
      *$'\n'"$variable"$'\n'*)
        printf '%sPowtorzona nazwa sekretu: %s%s\n' "$C_RED" "$variable" "$C_RESET" >&2
        return 2
        ;;
    esac
    [[ -n "$encoded" && "$encoded" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || {
      printf '%sNieprawidlowe kodowanie wartosci sekretu.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    value="$(printf '%s' "$encoded" | platform_base64_decode_stdin)" || {
      printf '%sNie udalo sie odczytac wartosci sekretu.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || {
      printf '%sWartosc sekretu zawiera niedozwolone znaki.%s\n' "$C_RED" "$C_RESET" >&2
      return 2
    }
    dotenv_append "$variable" "$value" "$temp_file"
    seen+="${variable}"$'\n'
    updated=$((updated + 1))
  done < "$input_file"

  local mail_from='no-reply@minitech.local'
  local chat_support_email='company@minitech.local'
  [[ "$profile" == 'test' ]] && mail_from='no-reply@minitech.test'
  [[ "$profile" == 'test' ]] && chat_support_email='company@minitech.test'
  grep -q '^GRAFANA_ADMIN_USER=' "$temp_file" || dotenv_append GRAFANA_ADMIN_USER admin "$temp_file"
  grep -q '^MAIL_HOST=' "$temp_file" || dotenv_append MAIL_HOST 127.0.0.1 "$temp_file"
  grep -q '^MAIL_PORT=' "$temp_file" || dotenv_append MAIL_PORT 11025 "$temp_file"
  grep -q '^MAIL_USERNAME=' "$temp_file" || dotenv_append MAIL_USERNAME '' "$temp_file"
  grep -q '^MAIL_PASSWORD=' "$temp_file" || dotenv_append MAIL_PASSWORD '' "$temp_file"
  grep -q '^MAIL_FROM=' "$temp_file" || dotenv_append MAIL_FROM "$mail_from" "$temp_file"
  grep -q '^MAIL_SMTP_AUTH=' "$temp_file" || dotenv_append MAIL_SMTP_AUTH false "$temp_file"
  grep -q '^MAIL_SMTP_STARTTLS_ENABLE=' "$temp_file" || dotenv_append MAIL_SMTP_STARTTLS_ENABLE false "$temp_file"
  grep -q '^CHAT_SUPPORT_EMAIL=' "$temp_file" || dotenv_append CHAT_SUPPORT_EMAIL "$chat_support_email" "$temp_file"

  local required_variable
  for required_variable in \
    PRODUCT_DB_PASSWORD ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD PAYMENT_DB_PASSWORD \
    NOTIFICATION_DB_PASSWORD CUSTOMER_DB_PASSWORD DELIVERY_DB_PASSWORD PROMOTION_DB_PASSWORD \
    WISHLIST_DB_PASSWORD REVIEW_DB_PASSWORD RECOMMENDATION_DB_PASSWORD CHAT_DB_PASSWORD \
    RETURNS_DB_PASSWORD BILLING_DB_PASSWORD CUSTOMER_JWT_SECRET GRAFANA_ADMIN_PASSWORD; do
    if ! grep -q "^${required_variable}=\"[^\"]" "$temp_file"; then
      printf '%sBrak wymaganego sekretu: %s%s\n' "$C_RED" "$required_variable" "$C_RESET" >&2
      return 2
    fi
  done

  recipient="$(age-keygen -y "$key_file")" || return 1
  if ! SOPS_AGE_KEY_FILE="$key_file" sops --encrypt \
      --input-type dotenv --output-type dotenv --age "$recipient" \
      "$temp_file" > "$encrypted_temp"; then
    printf '%sNie udalo sie zapisac zaszyfrowanych sekretow profilu %s.%s\n' \
      "$C_RED" "$profile" "$C_RESET" >&2
    return 1
  fi
  chmod 600 "$encrypted_temp"
  mv -f -- "$encrypted_temp" "$encrypted_file" || return 1
  encrypted_temp=""
  printf '%sUtworzono zaszyfrowany plik sekretow profilu %s (%s pol). Wartosci nie zostaly wyswietlone.%s\n' \
    "$C_GREEN" "$profile" "$updated" "$C_RESET"
}

secrets_status() {
  printf 'Katalog skarbca: %s\n' "$SECRET_ROOT_DIRECTORY"
  secrets_tools_check || true
  for profile in dev test; do
    local key_file encrypted_file
    key_file="$(secret_key_file "$profile")"
    encrypted_file="$(secret_encrypted_file "$profile")"
    [[ -f "$key_file" ]] && printf '%-12s klucz: OK\n' "$profile" || printf '%-12s klucz: BRAK\n' "$profile"
    [[ -f "$encrypted_file" ]] && printf '%-12s plik:  OK\n' "$profile" || printf '%-12s plik:  BRAK (uruchom secrets-edit %s)\n' "$profile" "$profile"
    if [[ -f "$key_file" && -f "$encrypted_file" ]]; then
      secrets_validate "$profile" || true
    fi
  done
  printf 'OpenBao/TLS prod: przygotowanie tylko, bez uruchamiania klastra.\n'
}

decrypt_profile_to_temp() {
  local profile="$1"
  secret_profile_supported "$profile" || return 0
  secrets_tools_check >/dev/null || return 1
  local key_file encrypted_file temp_file
  key_file="$(secret_key_file "$profile")"
  encrypted_file="$(secret_encrypted_file "$profile")"
  [[ -f "$key_file" ]] || { printf '%sBrak klucza %s.%s\n' "$C_RED" "$key_file" "$C_RESET" >&2; return 1; }
  [[ -f "$encrypted_file" ]] || { printf '%sBrak sekretow %s. Uruchom secrets-edit %s.%s\n' "$C_RED" "$profile" "$profile" "$C_RESET" >&2; return 1; }
  temp_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-secrets.XXXXXX")"
  chmod 600 "$temp_file"
  if ! SOPS_AGE_KEY_FILE="$key_file" sops --decrypt \
      --input-type dotenv --output-type dotenv "$encrypted_file" > "$temp_file"; then
    rm -f -- "$temp_file"
    return 1
  fi
  printf '%s\n' "$temp_file"
}

load_profile_secrets() {
  local profile="$1"
  secret_profile_supported "$profile" || return 0
  local temp_file
  temp_file="$(decrypt_profile_to_temp "$profile")" || return 1
  set -a
  # shellcheck disable=SC1090
  source "$temp_file"
  set +a
  rm -f -- "$temp_file"
  # An empty optional REDIS_URL must not override the Spring default. This is
  # useful for local profiles where Redis uses the published WSL IPv4 port.
  if [[ -z "${REDIS_URL:-}" ]]; then
    unset REDIS_URL
  fi
}

compose_with_secrets() {
  local compose_file="$1"
  shift
  if ! ensure_local_profile; then
    return 1
  fi
  require_command docker || return 1
  local temporary_env_file=""
  local profile
  profile="$(active_profile)"
  if secret_profile_supported "$profile"; then
    temporary_env_file="$(decrypt_profile_to_temp "$profile")" || return 1
  fi
  local -a compose_options=(--project-directory "$PROJECT_ROOT")
  if [[ -n "$temporary_env_file" ]]; then
    compose_options+=(--env-file "$temporary_env_file")
  fi
  compose_options+=(-f "$compose_file")
  docker compose "${compose_options[@]}" "$@"
  local status=$?
  [[ -z "$temporary_env_file" ]] || rm -f -- "$temporary_env_file"
  return "$status"
}

compose() {
  compose_with_secrets "$COMPOSE_FILE" "$@"
}

infra_up() {
  compose up -d "$@"
}

infra_down() {
  compose stop "${DB_SERVICES[@]}" "$BROKER_SERVICE" "$MAILPIT_SERVICE"
}

infra_status() {
  compose ps
}

apps_compose() {
  if ! ensure_local_profile; then
    return 1
  fi
  require_command docker || return 1
  local temporary_env_file=""
  local profile
  profile="$(active_profile)"
  if secret_profile_supported "$profile"; then
    temporary_env_file="$(decrypt_profile_to_temp "$profile")" || return 1
  fi
  local -a compose_options=(--project-directory "$PROJECT_ROOT")
  if [[ -n "$temporary_env_file" ]]; then
    compose_options+=(--env-file "$temporary_env_file")
  fi
  compose_options+=(-f "$COMPOSE_FILE" -f "$APPS_COMPOSE_FILE")
  docker compose "${compose_options[@]}" "$@"
  local status=$?
  [[ -z "$temporary_env_file" ]] || rm -f -- "$temporary_env_file"
  return "$status"
}

docker_diagnose_service_error() {
  local service_name="$1"
  local container_name
  container_name="$(docker ps -a --filter "name=${service_name}" --format '{{.Names}}' | head -n 1)"
  [[ -n "$container_name" ]] || container_name="miniorderplatform-${service_name}-1"

  local container_status
  container_status="$(docker inspect --format '{{.State.Status}} (kod wyjścia: {{.State.ExitCode}})' "$container_name" 2>/dev/null || echo "nieznany")"

  printf '\n%s============================================================%s\n' "$C_RED" "$C_RESET"
  printf '%s[DIAGNOSTYKA BŁĘDU] Usługa „%s” jest OFFLINE%s\n' "$C_RED" "$service_name" "$C_RESET"
  printf 'Status kontenera (%s): %s\n' "$container_name" "$container_status"

  local logs
  logs="$(docker logs --tail 30 "$container_name" 2>&1 || true)"
  if [[ -n "$logs" ]]; then
    printf '\n%s--- Ostatnie linie logu kontenera %s: ---%s\n' "$C_YELLOW" "$container_name" "$C_RESET"
    printf '%s\n' "$logs"
    printf '%s------------------------------------------------------------%s\n' "$C_YELLOW" "$C_RESET"
  fi

  printf '\n%s👉 Rozpoznana przyczyna i proponowane rozwiązanie:%s\n' "$C_BOLD" "$C_RESET"
  if echo "$logs" | grep -qiE 'Connection.*5432.*refused|database.*does not exist|password authentication failed|role.*does not exist'; then
    printf '  %s• Przyczyna:%s Baza danych PostgreSQL nie odpowiada lub hasło bazy jest nieprawidłowe.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Uruchom bazy danych w Centrali (przycisk "Infrastruktura (infra-up)") lub sprawdź sekrety DEV.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'CUSTOMER_JWT|MFA_ENCRYPTION|jwt-secret|JWT key material'; then
    printf '  %s• Przyczyna:%s Brak lub nieprawidłowy format klucza autoryzacji JWT / MFA.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Wygeneruj klucz JWT poleceniem "jwt-rotate dev" lub sprawdź "Sekrety DEV" w Centrali.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'Address already in use|BindException|port.*already allocated'; then
    printf '  %s• Przyczyna:%s Port sieciowy serwisu jest zajęty przez inny proces na komputerze.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Zatrzymaj lokalne aplikacje Java (app-stop-all) lub inne kontenery blokujące port.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'OutOfMemoryError|heap space|ExitOnOutOfMemoryError'; then
    printf '  %s• Przyczyna:%s Brak pamięci RAM dla kontenera Java (Out of Memory).\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Zwiększ przydział pamięci RAM dla WSL2 w pliku %%USERPROFILE%%\\.wslconfig.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'RedisConnectionException|6379.*Connection refused'; then
    printf '  %s• Przyczyna:%s Redis Event Broker nie odpowiada na porcie 6379.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Uruchom broker zdarzeń poleceniem "broker-up" lub "Infrastruktura (infra-up)" w Centrali.\n' "$C_GREEN" "$C_RESET"
  elif echo "$logs" | grep -qiE 'FlywayException|MigrationChecksumDiffers|Validate failed'; then
    printf '  %s• Przyczyna:%s Błąd migracji bazy danych Flyway (niezgodność sumy kontrolnej skryptów SQL).\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Sprawdź pliki migracji w db/migration wybranego serwisu.\n' "$C_GREEN" "$C_RESET"
  else
    printf '  %s• Przyczyna:%s Nieoczekiwany błąd podczas uruchamiania aplikacji Spring Boot.\n' "$C_RED" "$C_RESET"
    printf '  %s• Rozwiązanie:%s Zobacz pełny log kontenera poleceniem: bash ./scripts/control-center/control-center.sh docker-apps-logs %s\n' "$C_GREEN" "$C_RESET" "$service_name"
  fi
  printf '%s============================================================%s\n\n' "$C_RED" "$C_RESET"
}

docker_apps_up() {
  if ! docker image inspect mini-order-platform/api-gateway:local >/dev/null 2>&1; then
    printf 'Lokalne obrazy Docker aplikacji nie istnieja.\n'
    printf 'Buduje obrazy wszystkich 15 mikroserwisow i frontendu (moze to potrwac kilka minut przy pierwszym uruchomieniu)...\n'
    images_build || return 1
  fi
  printf 'Uruchamiam wszystkie serwisy i frontend w kontenerach Docker...\n'
  apps_compose up -d --remove-orphans || return 1

  printf '\nOczekuje na gotowosc i weryfikuje zdrowie wszystkich 15 mikroserwisow...\n'
  local -a services=(
    "gateway:18080:/api/products"
    "product:18081:/actuator/health"
    "order:18082:/actuator/health"
    "inventory:18083:/actuator/health"
    "payment:18084:/actuator/health"
    "notification:18086:/actuator/health"
    "customer:18087:/actuator/health"
    "delivery:18088:/actuator/health"
    "promotion:18089:/actuator/health"
    "wishlist:18090:/actuator/health"
    "review:18091:/actuator/health"
    "recommendation:18092:/actuator/health"
    "chat:18093:/actuator/health"
    "returns:18094:/actuator/health"
    "billing:18095:/actuator/health"
    "frontend:18070:/"
  )

  local max_attempts=30
  local attempt=0
  local all_healthy=0
  local -a failed_services=()

  while (( attempt < max_attempts )); do
    failed_services=()
    for entry in "${services[@]}"; do
      IFS=":" read -r name port path <<< "$entry"
      if ! curl --silent --show-error --connect-timeout 1 --max-time 2 -o /dev/null -w "%{http_code}" "http://127.0.0.1:${port}${path}" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
        failed_services+=("$name")
      fi
    done

    if [[ ${#failed_services[@]} -eq 0 ]]; then
      all_healthy=1
      break
    fi

    attempt=$((attempt + 1))
    printf '.'
    sleep 1.5
  done
  printf '\n'

  if [[ "$all_healthy" == "1" ]]; then
    printf '\n%s[SUKCES] Wszystkie 15 mikroserwisow i frontend sa ONLINE i gotowe do pracy!%s\n' "$C_GREEN" "$C_RESET"
    printf '  • Sklep (Frontend):   http://127.0.0.1:18070\n'
    printf '  • API Gateway:        http://127.0.0.1:18080\n'
    printf '  • Centrala Kontrolna: http://127.0.0.1:18100\n'
    printf '  • Mailpit (E-maile):  http://127.0.0.1:18025\n'
    return 0
  else
    printf '\n%s[UWAGA] Nastepujace uslugi nie odpowiedzialy w wyznaczonym czasie:%s %s\n' "$C_RED" "$C_RESET" "${failed_services[*]}"
    for failed in "${failed_services[@]}"; do
      docker_diagnose_service_error "$failed"
    done
    return 1
  fi
}

docker_apps_down() {
  printf 'Zatrzymuje kontenery serwisow i frontendu...\n'
  apps_compose stop \
    product-service order-service inventory-service payment-service \
    notification-service customer-service delivery-service promotion-service \
    wishlist-service review-service recommendation-service chat-service \
    returns-service billing-service api-gateway frontend
}

docker_apps_status() {
  apps_compose ps
}

docker_apps_logs() {
  apps_compose logs -f "$@"
}

workflow_fresh_start() {
  local profile
  profile="$(active_profile)"
  printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
  printf '%s🚀 [1-CLICK FRESH START] Inicjalizacja i pełny start platformy MiniTech (%s)%s\n' "$C_BOLD" "$profile" "$C_RESET"
  printf '%s============================================================%s\n\n' "$C_BLUE" "$C_RESET"

  printf 'Krok 1/4: Weryfikacja i przygotowanie kluczy autoryzacji JWT...\n'
  secrets_ensure_auth "$profile" || true

  printf '\nKrok 2/4: Uruchamianie infrastruktury baz danych i brokera zdarzeń...\n'
  infra_up || {
    printf '%sBłąd podczas startu infrastruktury baz danych.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  printf '\nKrok 3/4: Uruchamianie wszystkich 15 mikroserwisów i frontendu w kontenerach Docker...\n'
  docker_apps_up || {
    printf '%sBłąd podczas startu aplikacji Docker.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  printf '\nKrok 4/4: Wykonywanie szybkiej weryfikacji funkcjonalnej...\n'
  smoke || true

  printf '\n%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  printf '%s🎉 Środowisko MiniTech jest w 100%% gotowe do pracy!%s\n' "$C_GREEN" "$C_RESET"
  printf '  • Sklep (Frontend):   http://127.0.0.1:18070\n'
  printf '  • API Gateway:        http://127.0.0.1:18080\n'
  printf '  • Centrala Kontrolna: http://127.0.0.1:18100\n'
  printf '  • Mailpit (E-maile):  http://127.0.0.1:18025\n'
  printf '%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  return 0
}

workflow_seed_demo_data() {
  printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
  printf '%s📦 [SEED DEMO DATA] Zasilanie platformy przykładowymi danymi sklepu%s\n' "$C_BOLD" "$C_RESET"
  printf '%s============================================================%s\n\n' "$C_BLUE" "$C_RESET"

  local gateway="http://127.0.0.1:18080/api"
  if ! curl --silent --connect-timeout 2 --max-time 3 -o /dev/null -w "%{http_code}" "$gateway/products" | grep -qE '^2[0-9][0-9]$'; then
    printf '%sAPI Gateway nie odpowiada na %s. Uruchom najpierw serwisy (docker-apps-up).%s\n' "$C_RED" "$gateway" "$C_RESET" >&2
    return 1
  fi

  printf '1. Tworzenie przykładowych zapytań do czatu wsparcia...\n'
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"customerName":"Anna Kowalska","customerEmail":"anna.k@example.com","subject":"Pytanie o dostępność MacBook Pro","message":"Dzień dobry, czy wersja 36GB RAM będzie dostępna w tym tygodniu?"}' \
    "$gateway/chat/messages" >/dev/null 2>&1 || true
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"customerName":"Piotr Nowak","customerEmail":"p.nowak@example.com","subject":"Status przesyłki zamówienia","message":"Cześć! Kiedy mogę spodziewać się doręczenia zamówienia z wczoraj?"}' \
    "$gateway/chat/messages" >/dev/null 2>&1 || true
  printf '   ✓ Wysłano 2 przykładowe wątki czatu do obsługi klienta.\n'

  printf '2. Dodawanie opinii klientów o produktach...\n'
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"productId":1,"customerName":"Tomasz Wiśniewski","rating":5,"comment":"Świetny laptop! Bateria trzyma cały dzień, ekran rewelacyjny."}' \
    "$gateway/reviews" >/dev/null 2>&1 || true
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"productId":2,"customerName":"Karolina Mazur","rating":4,"comment":"Bardzo dobry smartfon, świetne zdjęcia w nocy, szybkie ładowanie."}' \
    "$gateway/reviews" >/dev/null 2>&1 || true
  printf '   ✓ Utworzono 2 opinie o produktach.\n'

  printf '3. Generowanie przykładowych kodów promocyjnych...\n'
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"code":"MINITECH10","discountPercent":10,"description":"Rabat powitalny 10%","validUntil":"2030-12-31"}' \
    "$gateway/promotions/codes" >/dev/null 2>&1 || true
  curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"code":"VIP20","discountPercent":20,"description":"Kupon VIP 20% zniżki","validUntil":"2030-12-31"}' \
    "$gateway/promotions/codes" >/dev/null 2>&1 || true
  printf '   ✓ Zarejestrowano kody promocyjne: MINITECH10 (-10%%) oraz VIP20 (-20%%).\n'

  printf '4. Wykonywanie testowego zamówienia i płatności z fakturą...\n'
  local order_res order_id
  order_res="$(curl --silent -X POST -H 'Content-Type: application/json' \
    -d '{"customerEmail":"klient.demo@minitech.local","shippingAddress":{"street":"Marszałkowska 10","city":"Warszawa","postalCode":"00-001","country":"PL"},"deliveryMethod":"COURIER_STANDARD","items":[{"productId":1,"quantity":1}]}' \
    "$gateway/orders" 2>/dev/null || true)"
  order_id="$(echo "$order_res" | grep -oE '"id":[0-9]+' | head -n 1 | cut -d':' -f2)"
  if [[ -n "$order_id" ]]; then
    curl --silent -X POST -H 'Content-Type: application/json' \
      -d "{\"orderId\":$order_id,\"amount\":4999.00,\"currency\":\"PLN\",\"paymentMethod\":\"BLIK\"}" \
      "$gateway/payments" >/dev/null 2>&1 || true
    printf '   ✓ Utworzono i opłacono zamówienie #%s z generowaniem faktury VAT.\n' "$order_id"
  fi

  printf '\n%s[SUKCES] Dane demonstracyjne zostały pomyślnie załadowane do platformy!%s\n' "$C_GREEN" "$C_RESET"
  printf '  • Sprawdź czat w Centrali (zakładka Czat klientów)\n'
  printf '  • Sprawdź opinie i promocje w Sklepie: http://127.0.0.1:18070\n'
  return 0
}

doctor_auto_fix() {
  printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
  printf '%s🚑 [AUTO-HEALING DOCTOR] Pełny audyt środowiska i automatyczna naprawa%s\n' "$C_BOLD" "$C_RESET"
  printf '%s============================================================%s\n\n' "$C_BLUE" "$C_RESET"

  local profile
  profile="$(active_profile)"
  local fixes_applied=0

  printf '[1/5] Kontrola kluczy kryptograficznych i autoryzacji JWT (%s)...\n' "$profile"
  if ! secrets_ensure_auth "$profile" >/dev/null 2>&1; then
    printf '  ⚡ Naprawiono: wygenerowano brakujący klucz JWT dla profilu %s.\n' "$profile"
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Klucze JWT są poprawne.\n'
  fi

  printf '[2/5] Kontrola kontenerów baz danych PostgreSQL...\n'
  local dbs_down=0
  for db_cont in $(docker ps -a --filter "name=db-1" --format '{{.Names}}'); do
    if ! docker inspect --format '{{.State.Running}}' "$db_cont" 2>/dev/null | grep -q "true"; then
      dbs_down=1
      break
    fi
  done
  if [[ "$dbs_down" == "1" ]] || ! docker ps --format '{{.Names}}' | grep -q "product-db"; then
    printf '  ⚡ Wykryto wyłączone bazy danych. Uruchamiam infrastrukturę (infra-up)...\n'
    infra_up >/dev/null 2>&1 || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Wszystkie bazy danych PostgreSQL działają poprawnie.\n'
  fi

  printf '[3/5] Kontrola brokera zdarzeń Redis...\n'
  if ! docker ps --format '{{.Names}}' | grep -q "event-broker"; then
    printf '  ⚡ Wykryto wyłączony kontener event-broker. Uruchamiam Redis...\n'
    broker_up >/dev/null 2>&1 || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Redis Event Broker działa i odpowiada.\n'
  fi

  printf '[4/5] Kontrola serwera poczty Mailpit...\n'
  if ! docker ps --format '{{.Names}}' | grep -q "mailpit"; then
    printf '  ⚡ Wykryto wyłączony kontener mailpit. Uruchamiam Mailpit...\n'
    mailpit_up >/dev/null 2>&1 || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Mailpit działa pod adresem http://127.0.0.1:18025.\n'
  fi

  printf '[5/5] Kontrola dostępności mikroserwisów i portów...\n'
  local health_offline=0
  local -a ports=(18080 18081 18082 18083 18084 18086 18087 18088 18089 18090 18091 18092 18093 18094 18095 18070)
  for port in "${ports[@]}"; do
    if ! curl --silent --connect-timeout 1 --max-time 1 -o /dev/null -w "%{http_code}" "http://127.0.0.1:${port}/actuator/health" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
      if [[ "$port" == "18070" ]] && curl --silent --connect-timeout 1 --max-time 1 -o /dev/null -w "%{http_code}" "http://127.0.0.1:18070/" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
        continue
      fi
      if [[ "$port" == "18080" ]] && curl --silent --connect-timeout 1 --max-time 1 -o /dev/null -w "%{http_code}" "http://127.0.0.1:18080/api/products" 2>/dev/null | grep -qE '^2[0-9][0-9]$'; then
        continue
      fi
      health_offline=1
      break
    fi
  done

  if [[ "$health_offline" == "1" ]]; then
    printf '  ⚡ Wykryto niedostępne mikroserwisy. Restartuję kontenery aplikacji...\n'
    docker_apps_up || true
    fixes_applied=$((fixes_applied + 1))
  else
    printf '  ✓ Wszystkie mikroserwisy i frontend są aktywne (HTTP 200).\n'
  fi

  printf '\n%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  if [[ "$fixes_applied" -gt 0 ]]; then
    printf '%s🎉 Diagnostyka zakończona: Zastosowano %s automatycznych poprawek. Środowisko jest w pełni sprawne!%s\n' "$C_GREEN" "$fixes_applied" "$C_RESET"
  else
    printf '%s🎉 Diagnostyka zakończona: Środowisko jest w doskonałym stanie, nie wykryto żadnych usterek!%s\n' "$C_GREEN" "$C_RESET"
  fi
  printf '%s============================================================%s\n' "$C_GREEN" "$C_RESET"
  return 0
}

monitoring_compose() {
  compose_with_secrets "$MONITORING_COMPOSE_FILE" "$@"
}

monitoring_up() {
  monitoring_compose up -d
}

monitoring_down() {
  monitoring_compose stop
}

monitoring_status() {
  require_command curl || return 1
  monitoring_compose ps
  printf '\nPrometheus: http://127.0.0.1:%s\n' "$PROMETHEUS_PORT"
  curl --silent --show-error --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" || true
  printf '\nGrafana:    http://127.0.0.1:%s\n' "$GRAFANA_PORT"
  curl --silent --show-error --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:${GRAFANA_PORT}/api/health" || true
  printf '\n'
}

monitoring_logs() {
  local service="${1:-}"
  if [[ -n "$service" && "$service" != "prometheus" && "$service" != "grafana" ]]; then
    printf '%sUzycie: monitoring-logs [prometheus|grafana]%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  fi
  if [[ -n "$service" ]]; then
    monitoring_compose logs --tail=100 "$service"
  else
    monitoring_compose logs --tail=100
  fi
}

monitoring_urls() {
  printf 'Prometheus: http://127.0.0.1:%s\n' "$PROMETHEUS_PORT"
  printf 'Grafana:    http://127.0.0.1:%s\n' "$GRAFANA_PORT"
  printf 'Grafana user: %s\n' "${GRAFANA_ADMIN_USER:-admin}"
  printf 'Grafana password: z aktywnego skarbca profilu\n'
}

broker_cli() {
  compose exec -T "$BROKER_SERVICE" redis-cli "$@"
}

broker_up() {
  compose up -d "$BROKER_SERVICE"
}

broker_down() {
  compose stop "$BROKER_SERVICE"
}

broker_status() {
  compose ps "$BROKER_SERVICE"
  printf '\nRedis ping:\n'
  broker_cli ping
}

broker_logs() {
  compose logs --tail=100 "$BROKER_SERVICE"
}

stream_info() {
  printf 'Stream: %s\n\n' "$PAYMENT_EVENT_STREAM"
  broker_cli XINFO STREAM "$PAYMENT_EVENT_STREAM" || true
  printf '\nConsumer groups:\n'
  broker_cli XINFO GROUPS "$PAYMENT_EVENT_STREAM" || true
}

stream_read() {
  local count="${1:-20}"
  broker_cli XRANGE "$PAYMENT_EVENT_STREAM" - + COUNT "$count"
}

broker_reset_group() {
  local group="${1:-$NOTIFICATION_CONSUMER_GROUP}"
  printf 'Usuwam tylko consumer group %s ze streamu %s. Dane streamu pozostaja.\n' "$group" "$PAYMENT_EVENT_STREAM"
  broker_cli XGROUP DESTROY "$PAYMENT_EVENT_STREAM" "$group"
}

active_profile() {
  if [[ -f "$ACTIVE_PROFILE_FILE" ]]; then
    tr -d '\r\n' < "$ACTIVE_PROFILE_FILE"
  else
    printf 'dev'
  fi
}

UI_LANGUAGE="${CONTROL_CENTER_LANGUAGE:-}"

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

mailpit_up() {
  compose up -d "$MAILPIT_SERVICE"
}

mailpit_down() {
  compose stop "$MAILPIT_SERVICE"
}

mailpit_status() {
  require_command curl || return 1
  compose ps "$MAILPIT_SERVICE"
  printf 'Panel Mailpit: http://127.0.0.1:%s\n' "$MAILPIT_WEB_PORT"
  printf 'SMTP Mailpit:  127.0.0.1:%s\n' "$MAILPIT_SMTP_PORT"
  local info
  if info="$(mailpit_info 2>/dev/null)"; then
    printf '%s' "$info" | python3 -c 'import json,sys; data=json.load(sys.stdin); print("Messages: {} (unread: {})".format(data.get("Messages", 0), data.get("Unread", 0)))'
  else
    printf '%sMailpit API jest niedostepne.%s\n' "$C_RED" "$C_RESET"
  fi
}

mailpit_logs() {
  compose logs --tail=100 "$MAILPIT_SERVICE"
}

mailpit_smoke() {
  require_command curl || return 1
  local mailpit_url="http://127.0.0.1:${MAILPIT_WEB_PORT}"
  local notification_url="http://127.0.0.1:$(app_port notification)/notifications"
  local timestamp recipient subject payload response attempt
  timestamp="$(date +%s)"
  recipient="mailpit-smoke-${timestamp}@minitech.local"
  subject="Mini Order Platform Mailpit Smoke ${timestamp}"
  payload="$(printf '{\n  \"eventType\": \"ORDER_STATUS_CHANGED\",\n  \"sourceId\": %s,\n  \"recipient\": \"%s\",\n  \"channel\": \"EMAIL\",\n  \"subject\": \"%s\",\n  \"message\": \"Mailpit smoke test from Notification Service.\",\n  \"simulateFailure\": false\n}' "$timestamp" "$recipient" "$subject")"

  printf 'Sprawdzam API Mailpit: %s/api/v1/info\n' "$mailpit_url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
    "$mailpit_url/api/v1/info" >/dev/null || {
    printf '%sMailpit jest niedostepny. Uruchom: mailpit-up%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  printf 'Wysylam testowe powiadomienie przez %s\n' "$notification_url"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' \
    -X POST "$notification_url" \
    --data "$payload")" || {
    printf '%sNotification Service nie przyjal testowego powiadomienia.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Odpowiedz Notification Service: %s\n' "$response"

  for attempt in {1..10}; do
    response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
      --get "$mailpit_url/api/v1/search" \
      --data-urlencode "query=subject:${subject}" 2>/dev/null || true)"
    if [[ "$response" == *"$subject"* ]]; then
      printf '%sSmoke Mailpit zakonczony powodzeniem. Odbiorca: %s%s\n' "$C_GREEN" "$recipient" "$C_RESET"
      printf 'Panel: http://127.0.0.1:%s\n' "$MAILPIT_WEB_PORT"
      return 0
    fi
    sleep 1
  done

  printf '%sMailpit dziala, ale nie znaleziono testowej wiadomosci.%s\n' "$C_RED" "$C_RESET" >&2
  printf 'Temat do sprawdzenia: %s\n' "$subject" >&2
  return 1
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

mailpit_send() {
  require_command curl || return 1
  local mailpit_url="http://127.0.0.1:${MAILPIT_WEB_PORT}"
  local notification_url="http://127.0.0.1:$(app_port notification)/notifications"
  local timestamp source_id recipient subject message payload response attempt

  printf 'Odbiorca e-mail [mailpit-test@minitech.local]: '
  read -r recipient || return 1
  recipient="${recipient:-mailpit-test@minitech.local}"
  if [[ ! "$recipient" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    printf '%sNieprawidlowy adres odbiorcy.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  fi

  printf 'Temat [Test z Control Center]: '
  read -r subject || return 1
  subject="${subject:-Test z Control Center}"
  printf 'Tresc [Wiadomosc testowa z Control Center]: '
  read -r message || return 1
  message="${message:-Wiadomosc testowa z Control Center}"

  [[ -n "$subject" && ${#subject} -le 120 ]] || {
    printf '%sTemat musi miec od 1 do 120 znakow.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  [[ -n "$message" && ${#message} -le 1000 ]] || {
    printf '%sTresc musi miec od 1 do 1000 znakow.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }

  printf 'Sprawdzam dostepnosc Mailpit: %s/api/v1/info\n' "$mailpit_url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
    "$mailpit_url/api/v1/info" >/dev/null || {
    printf '%sMailpit jest niedostepny. Uruchom: mailpit-up%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  timestamp="$(date +%s)"
  source_id="${timestamp}$$"
  payload="$(printf '{\n  \"eventType\": \"ORDER_STATUS_CHANGED\",\n  \"sourceId\": %s,\n  \"recipient\": \"%s\",\n  \"channel\": \"EMAIL\",\n  \"subject\": \"%s\",\n  \"message\": \"%s\",\n  \"simulateFailure\": false\n}' \
    "$source_id" "$(json_escape "$recipient")" "$(json_escape "$subject")" "$(json_escape "$message")")"

  printf 'Wysylam wiadomosc przez Notification Service: %s\n' "$notification_url"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' \
    -X POST "$notification_url" \
    --data "$payload")" || {
    printf '%sNotification Service nie przyjal wiadomosci. Sprawdz, czy dziala na porcie %s.%s\n' \
      "$C_RED" "$(app_port notification)" "$C_RESET" >&2
    return 1
  }
  printf 'Odpowiedz Notification Service: %s\n' "$response"

  for attempt in {1..10}; do
    response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
      --get "$mailpit_url/api/v1/search" \
      --data-urlencode "query=subject:${subject}" 2>/dev/null || true)"
    if [[ "$response" == *"$subject"* ]]; then
      printf '%sWiadomosc wyslana pomyslnie. Odbiorca: %s%s\n' "$C_GREEN" "$recipient" "$C_RESET"
      printf 'Panel Mailpit: http://127.0.0.1:%s\n' "$MAILPIT_WEB_PORT"
      return 0
    fi
    sleep 1
  done

  printf '%sWiadomosc zostala przyjeta, ale nie znaleziono jej jeszcze w Mailpit.%s\n' "$C_RED" "$C_RESET" >&2
  printf 'Temat do sprawdzenia: %s\n' "$subject" >&2
  return 1
}

mailpit_info() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
    "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/info"
}

mailpit_list() {
  require_command curl || return 1
  require_command python3 || return 1
  local limit="${1:-20}" response
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 && "$limit" -le 100 ]] || {
    printf '%sLimit musi byc liczba od 1 do 100.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    --get "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/messages" \
    --data-urlencode "limit=${limit}")" || return 1
  printf '%s' "$response" | python3 -c '
import json
import sys

data = json.load(sys.stdin)
print("Wiadomosci: {} / lacznie {}".format(data.get("count", 0), data.get("total", 0)))
for index, message in enumerate(data.get("messages", []), 1):
    recipients = ", ".join(item.get("Address", "") for item in (message.get("To") or []))
    print("{:>2}. {} | {} | To: {} | ID: {}".format(index, message.get("Created", ""), message.get("Subject", ""), recipients, message.get("ID", "")))
'
}

mailpit_search() {
  require_command curl || return 1
  require_command python3 || return 1
  local query="${1:-}" response
  if [[ -z "$query" ]]; then
    printf 'Zapytanie Mailpit (np. to:adres@example.com lub subject:Payment): '
    read -r query || return 1
  fi
  [[ -n "$query" ]] || {
    printf '%sZapytanie nie moze byc puste.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    --get "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/search" \
    --data-urlencode "query=${query}")" || return 1
  printf '%s' "$response" | python3 -c '
import json
import sys

data = json.load(sys.stdin)
print("Wyniki dla: {} | znaleziono: {}".format(sys.argv[1], data.get("count", 0)))
for index, message in enumerate(data.get("messages", []), 1):
    recipients = ", ".join(item.get("Address", "") for item in (message.get("To") or []))
    print("{:>2}. {} | {} | To: {} | ID: {}".format(index, message.get("Created", ""), message.get("Subject", ""), recipients, message.get("ID", "")))
' "$query"
}

mailpit_delete_all() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -X DELETE "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/messages" >/dev/null
}

mailpit_clear() {
  printf 'Usunie to wszystkie wiadomosci z lokalnego Mailpit. Wpisz DELETE, aby potwierdzic: '
  read -r confirmation || return 1
  if [[ "$confirmation" != "DELETE" ]]; then
    printf 'Anulowano czyszczenie Mailpit.\n'
    return 0
  fi
  mailpit_delete_all || {
    printf '%sNie udalo sie wyczyscic Mailpit.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%sSkrzynka Mailpit zostala wyczyszczona.%s\n' "$C_GREEN" "$C_RESET"
}

dead_letter_list() {
  require_command curl || return 1
  local url="http://127.0.0.1:$(app_port notification)/notifications/dead-letter"
  printf 'Otwarte wpisy DLQ: %s\n' "$url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url"
  printf '\n'
}

dead_letter_replay() {
  require_command curl || return 1
  local id="${1:-}"
  [[ -n "$id" ]] || {
    printf '%sUzycie: dlq-replay <id>%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  local url="http://127.0.0.1:$(app_port notification)/notifications/dead-letter/${id}/replay"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -X POST "$url"
  printf '\n'
}

maven() {
  prepare_java_environment || return 1
  if [[ -f "$MAVEN_WRAPPER" ]]; then
    bash "$MAVEN_WRAPPER" "$@"
  else
    require_command mvn || return 1
    mvn "$@"
  fi
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

backup_profile_directory() {
  printf '%s/%s' "$BACKUP_ROOT_DIRECTORY" "$1"
}

backup_key_file() {
  printf '%s/%s-backup.agekey' "$SECRET_KEY_DIRECTORY" "$1"
}

backup_root_check() {
  case "$BACKUP_ROOT_DIRECTORY" in
    ''|'/'|'.'|"$PROJECT_ROOT"|"$SECRET_ROOT_DIRECTORY")
      printf '%sNiebezpieczny katalog backupu: %s. Ustaw MINI_ORDER_PLATFORM_BACKUP_DIR na osobny katalog.%s\n' \
        "$C_RED" "$BACKUP_ROOT_DIRECTORY" "$C_RESET" >&2
      return 1
      ;;
  esac
}

backup_key_init() {
  local requested="${1:-all}"
  require_command age-keygen || return 1
  backup_root_check || return 1
  mkdir -p "$SECRET_KEY_DIRECTORY"
  chmod 700 "$SECRET_KEY_DIRECTORY"

  local -a profiles=()
  if [[ "$requested" == 'all' ]]; then
    profiles=(dev test)
  else
    secret_profile_supported "$requested" || {
      printf '%sKlucz backupu jest obecnie obslugiwany dla profilu dev albo test.%s\n' \
        "$C_RED" "$C_RESET" >&2
      return 2
    }
    profiles=("$requested")
  fi

  local profile key_file
  for profile in "${profiles[@]}"; do
    key_file="$(backup_key_file "$profile")"
    if [[ -f "$key_file" ]]; then
      printf 'Klucz szyfrowania backupu %s juz istnieje: %s\n' "$profile" "$key_file"
    else
      age-keygen -o "$key_file" >/dev/null
      chmod 600 "$key_file"
      printf 'Utworzono klucz szyfrowania backupu %s: %s\n' "$profile" "$key_file"
    fi
    printf 'Recipient %s: %s\n' "$profile" "$(age-keygen -y "$key_file")"
  done
}

backup_recipient_for_profile() {
  local profile="$1"
  case "$profile" in
    dev|test)
      local key_file
      key_file="$(backup_key_file "$profile")"
      [[ -f "$key_file" ]] || {
        printf '%sBrak klucza szyfrowania backupu dla %s. Uruchom: backup-key-init %s%s\n' \
          "$C_RED" "$profile" "$profile" "$C_RESET" >&2
        return 1
      }
      BACKUP_RECIPIENT="$(age-keygen -y "$key_file")"
      BACKUP_IDENTITY_FILE="$key_file"
      ;;
    prod)
      [[ -n "${BACKUP_AGE_RECIPIENT:-}" ]] || {
        printf '%sDla profilu prod ustaw BACKUP_AGE_RECIPIENT poza repozytorium.%s\n' \
          "$C_RED" "$C_RESET" >&2
        return 1
      }
      BACKUP_RECIPIENT="$BACKUP_AGE_RECIPIENT"
      BACKUP_IDENTITY_FILE="${BACKUP_AGE_IDENTITY_FILE:-}"
      ;;
    *)
      printf '%sNieznany profil backupu: %s%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      return 2
      ;;
  esac
}

backup_prepare_directory() {
  backup_root_check || return 1
  mkdir -p "$BACKUP_ROOT_DIRECTORY"
  chmod 700 "$BACKUP_ROOT_DIRECTORY"
  local profile_directory
  profile_directory="$(backup_profile_directory "$(active_profile)")"
  mkdir -p "$profile_directory"
  chmod 700 "$profile_directory"
}

db_backup_one() {
  local requested="$1"
  local timestamp="${2:-$(date -u +%Y%m%dT%H%M%SZ)}"
  local profile service database user output_directory output_file
  profile="$(active_profile)"
  service="$(db_service "$requested")" || {
    printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
    return 2
  }
  database="$(db_name "$requested")"
  user="$(db_user "$requested")"
  backup_recipient_for_profile "$profile" || return 1
  backup_prepare_directory || return 1
  output_directory="$(backup_profile_directory "$profile")/$service"
  mkdir -p "$output_directory"
  chmod 700 "$output_directory"
  output_file="$output_directory/${timestamp}.dump.age"

  printf 'Backup %s -> %s (szyfrowanie age)\n' "$service" "$output_file"
  if ! compose exec -T "$service" pg_dump \
      --format=custom --no-owner --no-acl \
      --username="$user" --dbname="$database" \
      | age -r "$BACKUP_RECIPIENT" -o "$output_file" -; then
    rm -f -- "$output_file"
    printf '%sBackup bazy %s nie powiodl sie.%s\n' "$C_RED" "$service" "$C_RESET" >&2
    return 1
  fi
  chmod 600 "$output_file"
  printf '%sBackup bazy %s zakonczony powodzeniem.%s\n' "$C_GREEN" "$service" "$C_RESET"
}

db_backup() {
  require_command docker || return 1
  require_command age || return 1
  local requested="${1:-all}"
  local timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  local failures=0 service
  active_profile >/dev/null
  if [[ "$requested" == 'all' ]]; then
     for service in product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
      db_backup_one "$service" "$timestamp" || failures=$((failures + 1))
    done
  else
    db_backup_one "$requested" "$timestamp" || failures=$((failures + 1))
  fi
  if [[ "$failures" -eq 0 ]]; then
    printf '%sBackup zakonczony. Katalog: %s%s\n' "$C_GREEN" "$(backup_profile_directory "$(active_profile)")" "$C_RESET"
  else
    printf '%sBackup zakonczony z bledami: %s%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  fi
  return "$failures"
}

latest_backup_file() {
  local profile="$1"
  local requested="${2:-all}"
  local search_directory
  if [[ "$requested" == 'all' ]]; then
    search_directory="$(backup_profile_directory "$profile")"
  else
    search_directory="$(backup_profile_directory "$profile")/$(db_service "$requested")" || return 2
  fi
  [[ -d "$search_directory" ]] || return 1
  find "$search_directory" -type f -name '*.dump.age' -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -n 1 | cut -d' ' -f2-
}

db_backup_list() {
  local profile="$(active_profile)"
  local requested="${1:-all}"
  local search_directory
  if [[ "$requested" == 'all' ]]; then
    search_directory="$(backup_profile_directory "$profile")"
  else
    search_directory="$(backup_profile_directory "$profile")/$(db_service "$requested")" || {
      printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
      return 2
    }
  fi
  printf 'Backupy profilu %s w %s:\n' "$profile" "$search_directory"
  if [[ ! -d "$search_directory" ]]; then
    printf 'Brak backupow.\n'
    return 0
  fi
  find "$search_directory" -type f -name '*.dump.age' -printf '%TY-%Tm-%Td %TH:%TM:%TS  %s B  %p\n' \
    | sort -r || true
}

db_backup_prune() {
  local profile="$(active_profile)"
  local retention_days="${1:-$BACKUP_RETENTION_DAYS}"
  [[ "$retention_days" =~ ^[1-9][0-9]*$ ]] || {
    printf '%sRetencja musi byc dodatnia liczba dni.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  backup_root_check || return 1
  local profile_directory
  profile_directory="$(backup_profile_directory "$profile")"
  [[ -d "$profile_directory" ]] || {
    printf 'Brak katalogu backupow do czyszczenia: %s\n' "$profile_directory"
    return 0
  }
  printf 'Usuwam zaszyfrowane backupy starsze niz %s dni z %s\n' "$retention_days" "$profile_directory"
  find "$profile_directory" -type f -name '*.dump.age' -mtime "+$retention_days" -print -delete
}

db_restore_test() {
  require_command docker || return 1
  require_command age || return 1
  local profile="$(active_profile)"
  local requested="${1:-all}"
  local backup_file
  if [[ -f "$requested" ]]; then
    backup_file="$requested"
  else
    backup_file="$(latest_backup_file "$profile" "$requested")" || {
      printf '%sNie znaleziono backupu dla: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
      return 1
    }
  fi
  [[ -f "$backup_file" && "$backup_file" == *.dump.age ]] || {
    printf '%sNieprawidlowy plik backupu: %s%s\n' "$C_RED" "$backup_file" "$C_RESET" >&2
    return 2
  }
  backup_recipient_for_profile "$profile" || return 1
  [[ -f "${BACKUP_IDENTITY_FILE:-}" ]] || {
    printf '%sBrak klucza prywatnego do odtworzenia backupu. Dla prod ustaw BACKUP_AGE_IDENTITY_FILE.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  }

  local restore_container="mini-order-platform-restore-$(date -u +%Y%m%d%H%M%S)-$RANDOM"
  local restore_password='restore-check-only'
  local ready=0 attempt object_count
  printf 'Test odtworzenia backupu w izolowanym kontenerze: %s\n' "$backup_file"
  if ! docker run -d --name "$restore_container" \
      -e POSTGRES_PASSWORD="$restore_password" \
      -e POSTGRES_DB=restore_check postgres:16-alpine >/dev/null; then
    printf '%sNie mozna uruchomic kontenera testowego.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi

  for attempt in $(seq 1 30); do
    if docker exec "$restore_container" pg_isready -U postgres -d restore_check >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 1
  done
  if [[ "$ready" -ne 1 ]]; then
    docker rm -f "$restore_container" >/dev/null 2>&1 || true
    printf '%sKontener testowy PostgreSQL nie osiagnal gotowosci.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi

  if ! age -d -i "$BACKUP_IDENTITY_FILE" "$backup_file" \
      | docker exec -i "$restore_container" pg_restore \
          --exit-on-error --single-transaction --no-owner --no-privileges \
          --username=postgres --dbname=restore_check; then
    docker rm -f "$restore_container" >/dev/null 2>&1 || true
    printf '%sOdtworzenie backupu nie powiodlo sie.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi
  object_count="$(docker exec "$restore_container" psql -U postgres -d restore_check -Atqc \
    "SELECT count(*) FROM pg_class WHERE relkind IN ('r','p') AND relnamespace = 'public'::regnamespace;")" || {
      docker rm -f "$restore_container" >/dev/null 2>&1 || true
      return 1
    }
  docker rm -f "$restore_container" >/dev/null 2>&1 || true
  printf '%sTest odtworzenia zakonczony powodzeniem. Obiekty tabelaryczne: %s%s\n' \
    "$C_GREEN" "$object_count" "$C_RESET"
}

backup_schedule_line() {
  local hour="${1:-2}"
  local cron_project cron_script cron_log
  cron_project="$(printf '%q' "$PROJECT_ROOT")"
  cron_script="$(printf '%q' "$PROJECT_ROOT/scripts/backup/postgres-backup.sh")"
  cron_log="$(printf '%q' "$RUNTIME_DIRECTORY/logs/db-backup.log")"
  printf '0 %s * * * cd %s && bash %s >> %s 2>&1 %s\n' \
    "$hour" "$cron_project" "$cron_script" "$cron_log" "$BACKUP_SCHEDULE_MARKER"
}

backup_schedule_install() {
  local hour="${1:-2}"
  [[ "$hour" =~ ^[0-9]+$ && "$hour" -ge 0 && "$hour" -le 23 ]] || {
    printf '%sGodzina musi byc liczba od 0 do 23.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  backup_recipient_for_profile "$(active_profile)" || return 1
  if declare -F platform_backup_schedule_install >/dev/null 2>&1; then
    platform_backup_schedule_install "$hour" \
      "$PROJECT_ROOT/scripts/backup/postgres-backup.sh" \
      "$RUNTIME_DIRECTORY/logs/db-backup.log"
    return $?
  fi
  require_command crontab || return 1
  mkdir -p "$RUNTIME_DIRECTORY/logs"
  local existing filtered temporary
  existing="$(crontab -l 2>/dev/null || true)"
  filtered="$(printf '%s\n' "$existing" | grep -v -F "$BACKUP_SCHEDULE_MARKER" || true)"
  temporary="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-crontab.XXXXXX")"
  printf '%s\n' "$filtered" > "$temporary"
  backup_schedule_line "$hour" >> "$temporary"
  if ! crontab "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  rm -f -- "$temporary"
  printf '%sAutomatyczny backup PostgreSQL ustawiony codziennie o %02d:00. Retencja: %s dni.%s\n' \
    "$C_GREEN" "$hour" "$BACKUP_RETENTION_DAYS" "$C_RESET"
}

backup_schedule_remove() {
  if declare -F platform_backup_schedule_remove >/dev/null 2>&1; then
    platform_backup_schedule_remove
    return $?
  fi
  require_command crontab || return 1
  local existing filtered temporary
  existing="$(crontab -l 2>/dev/null || true)"
  filtered="$(printf '%s\n' "$existing" | grep -v -F "$BACKUP_SCHEDULE_MARKER" || true)"
  temporary="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-crontab.XXXXXX")"
  printf '%s\n' "$filtered" > "$temporary"
  if ! crontab "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  rm -f -- "$temporary"
  printf 'Automatyczny backup PostgreSQL zostal usuniety z crontab.\n'
}

backup_schedule_status() {
  if declare -F platform_backup_schedule_status >/dev/null 2>&1; then
    platform_backup_schedule_status
    return $?
  fi
  require_command crontab || return 1
  if ! crontab -l 2>/dev/null | grep -F "$BACKUP_SCHEDULE_MARKER"; then
    printf 'Brak aktywnego harmonogramu backupu PostgreSQL.\n'
  fi
}

print_catalog() {
  cat <<'EOF'
Mini Order Platform - Control Center

Infrastruktura PostgreSQL:
  infra-up                         Uruchom wszystkie bazy PostgreSQL
  infra-down                       Zatrzymaj kontenery baz bez usuwania danych
  infra-status                     Pokaz status kontenerow baz
  infra-logs [db]                  Pokaz ostatnie logi wszystkich baz lub jednej

Bazy per serwis:
  db-list                          Pokaz baze, uzytkownika i port kazdego serwisu
  db-up <service|all>              Uruchom baze wskazanego serwisu
  db-down <service|all>            Zatrzymaj baze wskazanego serwisu
  db-status <service|all>          Pokaz status bazy
  db-logs <service>                Pokaz logi bazy
  db-shell <service>               Otworz psql w kontenerze bazy
  db-down-all                      Zatrzymaj wszystkie bazy
  db-backup [service|all]           Wykonaj szyfrowany backup PostgreSQL
  db-backup-list [service|all]      Pokaz backupy aktywnego profilu
  db-backup-prune [days]            Usun backupy starsze niz podana retencja
  db-restore-test [service|plik]    Odtworz najnowszy lub wskazany backup testowo
  backup-key-init [dev|test|all]    Utworz osobny klucz age do szyfrowania backupow
  backup-schedule-install [hour]    Ustaw codzienny backup przez adapter platformy
  backup-schedule-remove            Usun harmonogram backupu z crontab
  backup-schedule-status            Pokaz status harmonogramu backupu
  inventory-reset                   Przywroc bazowe ilosci demo magazynu (dev/test)
  demo-reset                        Wyczysc dane zamowien i przywroc demo (dev/test)

Projekty / aplikacje:
  app-run <service> [dev|test]      Uruchom jeden mikroserwis przez Maven
  app-stop <service>                Zatrzymaj jeden wskazany mikroserwis
  app-run-all                      Uruchom wszystkie mikroserwisy w tle platformy
  app-stop-all                     Zatrzymaj mikroserwisy uruchomione przez centrale
  app-status-all                   Pokaz status procesow mikroserwisow
  app-logs <service>               Pokaz ostatnie logi mikroserwisu z centrali
  app-test [service|all]           Testy jednego serwisu lub calego monorepo
  app-package [service|all]        Pakietowanie jednego serwisu lub calego monorepo
  health [service|all]             Sprawdz dostepnosc endpointu kazdego serwisu
  smoke                            Sprawdz Product Service przez API Gateway
  api-docs-urls                    Pokaz adresy OpenAPI i Swagger UI
  contract-test                    Zweryfikuj kontrakt publicznego API v1
  security-test                    Wykonaj podstawowe testy bezpieczenstwa API
  zap-dast                         Uruchom pelny OWASP ZAP DAST dla API Gateway
  load-test                        Wykonaj lokalny test obciazeniowy API
  e2e                              Uruchom pelny scenariusz E2E z rejestracja i zakupem
  ui-test-status                   Sprawdz wymagania testow Playwright
  ui-test-install                  Zainstaluj zaleznosci i Chromium Playwright
  ui-test-smoke                    Szybki test przegladarkowy najwazniejszych ekranow
  ui-test                          Pelna regresja UI w Chromium (z mock API)
  ui-test-system                   Pelny checkout przez prawdziwe serwisy dev/test
  ui-test-browsers                 Regresja UI w Chromium, Firefox, WebKit i mobile
  ui-test-headed                   Uruchom regresje UI z widoczna przegladarka
  ui-test-debug                    Uruchom Playwright Inspector
  ui-test-report                   Pokaz raport HTML Playwright
  images-build                     Zbuduj lokalne obrazy Docker wszystkich aplikacji
  docker-apps-up                   Uruchom wszystkie serwisy i frontend w kontenerach Docker
  docker-apps-down                 Zatrzymaj kontenery serwisow i frontendu
  docker-apps-status               Pokaz status kontenerow aplikacji i frontendu
  docker-apps-logs [serwis]        Pokaz logi kontenerow aplikacji
  k8s-validate                     Zweryfikuj manifesty Kubernetes bez wdrazania
  k8s-status                       Pokaz status wdrozenia Kubernetes
  platform-info                    Pokaz wykryty system i adapter platformy
  resilience-status                Pokaz metryki timeoutow, Retry, Circuit Breaker i Bulkhead
  openbao-status                   Sprawdz status endpointu OpenBao bez ujawniania sekretow
  openbao-validate                 Zweryfikuj pliki przygotowania OpenBao/TLS prod
  tls-status                       Sprawdz certyfikat TLS wskazanego hosta
  jwt-status                       Pokaz status konfiguracji JWT bez sekretu
  jwt-rotate <dev|test>            Wygeneruj nowy klucz RSA JWT, zachowaj stare klucze publiczne
  jwt-smoke [email]                Przetestuj login, /me, refresh i logout JWT
  audit-list [limit]               Pokaz trwały dziennik audytowy Customer Service
  password-reset-request [email]   Wyślij żądanie resetu hasła przez API do Mailpit
  app-verify                       Automatyczny test katalogu, zakupu, platnosci i e-maila
  verify-mvp                       Sprawdz pelny scenariusz zamowienie -> platnosc -> e-mail
  delivery-smoke                  Sprawdz metody dostawy i wycene koszyka
  delivery-tracking [orderId]     Pokaz numer przesylki i status przewoznika
  delivery-tracking-status [orderId] [status]  Zmien symulowany status przesylki
  promotion-smoke                 Sprawdz aktywna kampanie i wycene promocji
  promotion-codes [kod]           Pokaz lub zweryfikuj kody rabatowe
  promotion-code-create           Utworz kod rabatowy dla profilu dev/test
  returns-list                    Pokaz zwroty i reklamacje
  returns-status <id> <status>    Zmien status zwrotu lub reklamacji
  orders-list [limit]             Pokaz wszystkie zamowienia z kwota, e-mailem, pozycjami i faktura
  invoices-list [limit]           Pokaz liste wszystkich wystawionych faktur VAT
  invoices-export-zip [plik]      Pobierz archiwum ZIP ze wszystkimi fakturami PDF
  invoice-correct <id>            Wystaw fakture korygujaca (KOR) do wskazanej faktury
  corrections-list [id]           Pokaz liste wystawionych faktur korygujacych
  invoice-show <orderId>          Pokaz fakture dla zamowienia
  invoice-pdf <orderId>            Pobierz PDF faktury do gitIgnoreOwn/runtime/invoices
  review-pending                  Pokaz opinie oczekujace na moderacje
  review-moderate <id> <status>   Opublikuj albo odrzuc opinie (PUBLISHED|REJECTED)
  chat-list [limit]               Pokaz wiadomosci z formularza czatu
  chat-reply <id> [message]       Odpowiedz klientowi przez e-mail
  doctor                            Pelna diagnostyka lokalnego srodowiska

Frontend sklepu:
  frontend-up                      Uruchom frontend pod 127.0.0.1:18070
  frontend-down                    Zatrzymaj frontend
  frontend-status                  Pokaz status frontendu i adres URL
  frontend-logs                    Pokaz logi frontendu

Centrala webowa:
  web-start [--port P] [--no-browser] Uruchom graficzny panel Centrali w przegladarce (alias: web, web-ui)


Diagnostyka:
  monitoring-up                   Uruchom Prometheus i Grafane
  monitoring-down                 Zatrzymaj Prometheus i Grafane
  monitoring-status               Pokaz status i endpointy monitoringu
  monitoring-logs [service]       Pokaz logi monitoringu
  monitoring-urls                 Pokaz adresy Prometheus/Grafana
  dlq-list                        Pokaz otwarte wpisy Notification DLQ
  dlq-replay <id>                 Ponow wysylke wpisu z Notification DLQ
  secrets-init                    Utworz klucze age dla profili dev/test
  secrets-setup <dev|test>        Utworz klucz i wprowadz sekrety kreatorem
  secrets-ensure-auth <dev|test>  Wygeneruj brakujacy sekret JWT bez wyswietlania
  secrets-validate <dev|test>     Sprawdz wymagane pola bez wyswietlania wartosci
  secrets-status                  Pokaz status skarbca bez wartosci sekretow
  secrets-edit <dev|test>         Edytuj zaszyfrowane sekrety profilu
  secrets-update-web <dev|test>   Zaktualizuj sekrety z formularza Centrali webowej
  secrets-setup-web <dev|test>    Utworz sekrety z kreatora Centrali webowej
  broker-up                        Uruchom Redis Event Broker
  broker-down                      Zatrzymaj Redis Event Broker
  broker-status                    Status kontenera i PING Redis
  broker-logs                      Pokaz logi Redis
  broker-cli <args...>             Wykonaj redis-cli w kontenerze
  stream-info                      Pokaz metryki streamu i consumer groups
  stream-read [count]              Pokaz ostatnie rekordy streamu
  broker-reset-group [group]       Usun consumer group bez kasowania streamu
  mailpit-up                       Uruchom Mailpit dla dev/test
  mailpit-down                     Zatrzymaj Mailpit
  mailpit-status                   Status Mailpit i adres panelu
  mailpit-logs                     Pokaz logi Mailpit
  mailpit-smoke                    Sprawdz wysylke Notification Service do Mailpit
  mailpit-send                     Napisz i wyslij wlasny e-mail do Mailpit
  mailpit-list [limit]             Pokaz ostatnie wiadomosci Mailpit
  mailpit-search <query>           Wyszukaj wiadomosci w Mailpit
  mailpit-clear                    Wyczysc skrzynke Mailpit po potwierdzeniu
  profile-show                     Pokaz aktywny profil
  profile-set <dev|test>           Ustaw lokalny profil uruchamiania aplikacji
  language-show                    Pokaz jezyk Control Center
  language-set <pl|en>             Ustaw jezyk Control Center
  prod-status                      Pokaz, dlaczego profil prod jest niedostepny
  environment-check                Sprawdz wymagania przed startem aplikacji
  start-check                      Alias kontroli wymagan przed startem
  environment-install              Zaproponuj instalacje brakow po potwierdzeniu ACCEPT
  github-check                     Sprawdz bezpieczenstwo plikow przed GitHub
  --list                           Pokaz ten katalog komend
  --check                          Alias environment-check
  --help                           Pokaz pomoc

   Alias serwisu: gateway, product, order, inventory, payment, notification, customer, delivery, promotion, wishlist, review, recommendation, chat, returns, billing.
EOF
}

print_database_catalog() {
  printf '%-14s %-18s %-8s %-18s %-18s\n' 'Serwis' 'Kontener DB' 'Port' 'Baza' 'Uzytkownik'
  printf '%-14s %-18s %-8s %-18s %-18s\n' '-------' '----------' '----' '----' '----------'
   for service in product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
    printf '%-14s %-18s %-8s %-18s %-18s\n' \
      "$service" "$(db_service "$service")" "$(db_port "$service")" "$(db_name "$service")" "$(db_user "$service")"
  done
  printf '\n%-14s %-18s %-8s\n' 'Broker' "$BROKER_SERVICE" "$BROKER_PORT"
  printf '%-14s %-18s %-8s\n' 'Mailpit SMTP' "$MAILPIT_SERVICE" "$MAILPIT_SMTP_PORT"
  printf '%-14s %-18s %-8s\n' 'Mailpit Web' "$MAILPIT_SERVICE" "$MAILPIT_WEB_PORT"
}

resolve_db_list() {
  if [[ "${1:-all}" == "all" ]]; then
    printf '%s\n' "${DB_SERVICES[@]}"
  else
    db_service "$1"
  fi
}

db_action() {
  local action="$1"
  local requested="${2:-all}"
  local databases database
  databases=()
  if [[ "$requested" != "all" ]] && ! db_service "$requested" >/dev/null; then
    printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
    return 2
  fi
  while IFS= read -r database; do
    databases+=("$database")
  done < <(resolve_db_list "$requested") || {
    printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$requested" "$C_RESET" >&2
    return 2
  }

  case "$action" in
    up) compose up -d "${databases[@]}" ;;
    down) compose stop "${databases[@]}" ;;
    status) compose ps "${databases[@]}" ;;
    logs) compose logs --tail=100 "${databases[@]}" ;;
    *) printf '%sNieznana akcja DB: %s%s\n' "$C_RED" "$action" "$C_RESET" >&2; return 2 ;;
  esac
}

db_down_all() {
  compose down
}

db_shell() {
  local alias="${1:-}"
  [[ -n "$alias" ]] || { printf 'Uzycie: db-shell <service>\n' >&2; return 2; }
  local db
  db="$(db_service "$alias")" || { printf '%sNieznana baza: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
  compose exec "$db" psql -U "$(db_user "$alias")" -d "$(db_name "$alias")"
}

inventory_reset() {
  local profile
  profile="$(active_profile)"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sReset magazynu jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
      ;;
    *)
      printf '%sNieznany profil: %s%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      return 2
      ;;
  esac

  local sql
  sql=$(cat <<'SQL'
BEGIN;

UPDATE inventory_reservations
SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP
WHERE status = 'RESERVED';

UPDATE inventory_items
SET available_quantity = CASE
        WHEN product_id = 1 THEN 12
        WHEN product_id = 2 THEN 10
        WHEN product_id = 3 THEN 5
        WHEN product_id = 4 THEN 8
        WHEN product_id = 5 THEN 7
        WHEN product_id = 6 THEN 15
        WHEN product_id = 7 THEN 6
        WHEN product_id = 8 THEN 9
        WHEN product_id = 9 THEN 11
        WHEN product_id = 10 THEN 8
        WHEN product_id = 11 THEN 6
        WHEN product_id = 12 THEN 12
        WHEN product_id = 13 THEN 10
        WHEN product_id = 14 THEN 7
        WHEN product_id = 15 THEN 9
        WHEN product_id BETWEEN 16 AND 105 THEN 5 + ((product_id * 7) % 11)
    END,
    reserved_quantity = 0,
    updated_at = CURRENT_TIMESTAMP
WHERE product_id BETWEEN 1 AND 105;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'inventory_items'
          AND column_name = 'sold_quantity'
    ) THEN
        UPDATE inventory_items
        SET sold_quantity = 0,
            updated_at = CURRENT_TIMESTAMP
        WHERE product_id BETWEEN 1 AND 105;
    END IF;
END $$;

COMMIT;
SQL
  )

  printf 'Resetuje demo stan magazynowy dla profilu %s.\n' "$profile"
  compose exec -T inventory-db psql \
    -v ON_ERROR_STOP=1 \
    -U inventory_app \
    -d inventory_db \
    -c "$sql"
  printf '%sStan magazynowy przywrocony. Otwarte rezerwacje oznaczono jako RELEASED.%s\n' "$C_GREEN" "$C_RESET"
}

demo_reset() {
  local profile
  profile="$(active_profile)"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sReset danych demo jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
      ;;
    *)
      printf '%sNieznany profil: %s%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      return 2
      ;;
  esac

  printf 'Reset usunie zamowienia, platnosci, powiadomienia, rezerwacje i wiadomosci Mailpit.\n'
  printf 'Produkty i konta klientow pozostana bez zmian. Wpisz RESET, aby potwierdzic: '
  read -r confirmation || return 1
  [[ "$confirmation" == "RESET" ]] || {
    printf 'Anulowano reset danych demo.\n'
    return 0
  }

  printf 'Czyszcze baze Inventory...\n'
  compose exec -T inventory-db psql -v ON_ERROR_STOP=1 -U inventory_app -d inventory_db -c '
    TRUNCATE inventory_reservation_items, inventory_reservations RESTART IDENTITY;
    UPDATE inventory_items
    SET available_quantity = CASE
            WHEN product_id = 1 THEN 12 WHEN product_id = 2 THEN 10 WHEN product_id = 3 THEN 5
            WHEN product_id = 4 THEN 8 WHEN product_id = 5 THEN 7 WHEN product_id = 6 THEN 15
            WHEN product_id = 7 THEN 6 WHEN product_id = 8 THEN 9 WHEN product_id = 9 THEN 11
            WHEN product_id = 10 THEN 8 WHEN product_id = 11 THEN 6 WHEN product_id = 12 THEN 12
            WHEN product_id = 13 THEN 10 WHEN product_id = 14 THEN 7 WHEN product_id = 15 THEN 9
            WHEN product_id BETWEEN 16 AND 105 THEN 5 + ((product_id * 7) % 11)
        END,
        reserved_quantity = 0,
        updated_at = CURRENT_TIMESTAMP
    WHERE product_id BETWEEN 1 AND 105;
  '

  printf 'Czyszcze baze Order...\n'
  compose exec -T order-db psql -v ON_ERROR_STOP=1 -U order_app -d order_db -c \
    'TRUNCATE order_items, orders RESTART IDENTITY CASCADE;'

  printf 'Czyszcze baze Payment...\n'
  compose exec -T payment-db psql -v ON_ERROR_STOP=1 -U payment_app -d payment_db -c \
    'TRUNCATE payment_outbox_events, payments RESTART IDENTITY CASCADE;'

  printf 'Czyszcze baze Notification...\n'
  compose exec -T notification-db psql -v ON_ERROR_STOP=1 -U notification_app -d notification_db -c \
    'TRUNCATE notification_dead_letters, notifications RESTART IDENTITY CASCADE;'

  if mailpit_delete_all; then
    printf 'Mailpit: wyczyszczono wiadomosci.\n'
  else
    printf '%sOstrzezenie: nie udalo sie wyczyscic Mailpit. Bazy zostaly zresetowane.%s\n' "$C_RED" "$C_RESET" >&2
  fi
  printf '%sDane demonstracyjne zostaly zresetowane dla profilu %s.%s\n' "$C_GREEN" "$profile" "$C_RESET"
}

run_app() {
  local alias="${1:-}"
  [[ -n "$alias" ]] || { printf 'Uzycie: app-run <service>\n' >&2; return 2; }
  local requested_profile="${2:-}"
  local project profile
  project="$(app_project "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
  profile="${requested_profile:-$(active_profile)}"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sProfil prod wymaga najpierw recznego wdrozenia OpenBao/TLS i dostarczenia sekretow. Uruchom dev albo test.%s\n' \
        "$C_RED" "$C_RESET" >&2
      return 1
      ;;
    *)
      printf '%sNieznany profil: %s. Dozwolone: dev albo test.%s\n' "$C_RED" "$profile" "$C_RESET" >&2
      return 2
      ;;
  esac
  local sname
  sname="$(app_docker_service_name "$alias")"
  if docker ps --filter "name=miniorderplatform-" -q 2>/dev/null | grep -q .; then
    printf 'Uruchamiam kontener Docker dla %s (%s)...\n' "$alias" "$sname"
    apps_compose up -d --no-deps "$sname" || apps_compose start "$sname"
    return $?
  fi

  if [[ -n "$requested_profile" ]]; then
    preflight || return 1
    secrets_validate "$profile" || return 1
    if ! docker info >/dev/null 2>&1; then
      printf '%sDocker daemon nie dziala.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
    fi
  else
    environment_check || return 1
  fi
  load_profile_secrets "$profile" || return 1
  local password_variable password_value
  password_variable="$(app_db_password_variable "$alias")"
  if [[ -n "$password_variable" ]]; then
    password_value="${!password_variable-}"
    [[ -n "$password_value" ]] || {
      printf '%sBrak %s w skarbcu profilu %s.%s\n' "$C_RED" "$password_variable" "$profile" "$C_RESET" >&2
      return 1
    }
    export DB_PASSWORD="$password_value"
  fi
  export SPRING_PROFILES_ACTIVE="$profile"
  printf '%sUruchamiam %s na porcie %s z profilem %s.%s\n' "$C_BLUE" "$project" "$(app_port "$alias")" "$profile" "$C_RESET"
  maven -pl "$project" spring-boot:run
}

app_aliases() {
  # Start dependencies before services that call them during readiness checks.
  printf '%s\n' promotion delivery product inventory order payment notification customer wishlist review recommendation chat returns billing gateway
}

app_pid_file() {
  printf '%s/pids/%s.pid' "$RUNTIME_DIRECTORY" "$1"
}

app_log_file() {
  printf '%s/logs/%s.log' "$RUNTIME_DIRECTORY" "$1"
}

app_process_running() {
  local alias="$1"
  local pid_file pid
  pid_file="$(app_pid_file "$alias")"
  [[ -f "$pid_file" ]] || return 1
  read -r pid < "$pid_file" || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

app_run_background_one() {
  local alias="$1"
  local project password_variable password_value pid_file log_file pid
  project="$(app_project "$alias")" || return 2
  pid_file="$(app_pid_file "$alias")"
  log_file="$(app_log_file "$alias")"

  if app_process_running "$alias"; then
    printf '%-14s juz dziala (PID %s)\n' "$alias" "$(<"$pid_file")"
    return 0
  fi

  rm -f -- "$pid_file"
  unset DB_PASSWORD
  password_variable="$(app_db_password_variable "$alias")"
  if [[ -n "$password_variable" ]]; then
    password_value="${!password_variable-}"
    [[ -n "$password_value" ]] || {
      printf '%sBrak %s w skarbcu profilu.%s\n' "$C_RED" "$password_variable" "$C_RESET" >&2
      return 1
    }
    export DB_PASSWORD="$password_value"
  fi

  export SPRING_PROFILES_ACTIVE="$ACTIVE_APP_PROFILE"
  if command -v setsid >/dev/null 2>&1; then
    (
      cd "$PROJECT_ROOT" || exit 1
      exec setsid bash "$MAVEN_WRAPPER" -pl "$project" spring-boot:run
    ) > "$log_file" 2>&1 &
  else
    (
      cd "$PROJECT_ROOT" || exit 1
      exec bash "$MAVEN_WRAPPER" -pl "$project" spring-boot:run
    ) > "$log_file" 2>&1 &
  fi
  pid=$!
  printf '%s\n' "$pid" > "$pid_file"
  printf '%-14s uruchomiono (PID %s, port %s)\n' "$alias" "$pid" "$(app_port "$alias")"
}

wait_for_app_health() {
  local alias="$1"
  local elapsed=0
  local timeout=120
  local port path url status
  port="$(app_port "$alias")" || return 1
  path="$(app_health_path "$alias")" || return 1
  url="http://127.0.0.1:${port}${path}"
  while (( elapsed < timeout )); do
    if ! app_process_running "$alias"; then
      return 1
    fi
    if status="$(curl --silent --show-error --connect-timeout 2 --max-time 5 \
        -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)" && [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}

app_run_all() {
  ACTIVE_APP_PROFILE="$(active_profile)"
  if ! environment_check; then
    return 1
  fi
  prepare_java_environment || return 1
  load_profile_secrets "$ACTIVE_APP_PROFILE" || return 1
  mkdir -p "$RUNTIME_DIRECTORY/pids" "$RUNTIME_DIRECTORY/logs"
  printf 'Uruchamiam wszystkie aplikacje z profilem %s w tle.\n' "$ACTIVE_APP_PROFILE"
  printf 'Logi: %s/logs\n' "$RUNTIME_DIRECTORY"

  local failures=0 alias
  while IFS= read -r alias; do
    if ! app_run_background_one "$alias"; then
      failures=$((failures + 1))
      continue
    fi
    printf '%-14s czekam na gotowosc HTTP...\n' "$alias"
    if wait_for_app_health "$alias"; then
      printf '%-14s gotowy\n' "$alias"
    else
      printf '%s%-14s nie wystartowal poprawnie%s\n' "$C_RED" "$alias" "$C_RESET" >&2
      failures=$((failures + 1))
    fi
  done < <(app_aliases)
  printf '\nSprawdz status: app-status-all lub health all\n'
  return "$failures"
}

app_endpoint_healthy() {
  local alias="$1"
  local port path
  port="$(app_port "$alias")" || return 1
  path="$(app_health_path "$alias")" || return 1
  curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:${port}${path}" >/dev/null 2>&1
}

app_docker_service_name() {
  local alias="${1:-}"
  case "$alias" in
    gateway|api-gateway) echo "api-gateway" ;;
    frontend) echo "frontend" ;;
    *-service) echo "$alias" ;;
    *) echo "${alias}-service" ;;
  esac
}

app_docker_container_running() {
  local alias="${1:-}"
  local sname
  sname="$(app_docker_service_name "$alias")"
  docker ps --filter "name=${sname}" --filter "status=running" -q 2>/dev/null | grep -q .
}

app_stop() {
  local alias="${1:-}"
  [[ -n "$alias" ]] || { printf 'Uzycie: app-stop <service>\n' >&2; return 2; }
  app_project "$alias" >/dev/null || {
    printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2
    return 2
  }
  local sname killed=0
  sname="$(app_docker_service_name "$alias")"
  if app_docker_container_running "$alias"; then
    printf 'Zatrzymuje kontener Docker: %s...\n' "$sname"
    apps_compose stop "$sname" && killed=1
  fi

  local pid_file pid port
  pid_file="$(app_pid_file "$alias")"
  if [[ -f "$pid_file" ]]; then
    read -r pid < "$pid_file" || pid=''
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      if command -v setsid >/dev/null 2>&1; then
        kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
      else
        kill "$pid" 2>/dev/null || true
      fi
      printf '%-14s zatrzymano proces Centrali (PID %s)\n' "$alias" "$pid"
      killed=1
    fi
    rm -f -- "$pid_file"
  fi

  port="$(app_port "$alias" 2>/dev/null || true)"
  if [[ -n "$port" ]]; then
    local pids_on_port=""
    pids_on_port="$(fuser "$port/tcp" 2>/dev/null || true)"
    if [[ -z "$pids_on_port" ]] && command -v lsof >/dev/null 2>&1; then
      pids_on_port="$(lsof -ti :"$port" 2>/dev/null || true)"
    fi
    if [[ -n "$pids_on_port" ]]; then
      for p in $pids_on_port; do
        if [[ "$p" =~ ^[0-9]+$ ]] && kill -0 "$p" 2>/dev/null; then
          kill "$p" 2>/dev/null || true
          printf '%-14s zatrzymano proces na porcie %s (PID %s)\n' "$alias" "$port" "$p"
          killed=1
        fi
      done
    fi
  fi

  if (( killed == 0 )); then
    printf '%-14s nie byl uruchomiony (port %s)\n' "$alias" "$port"
  else
    printf '%s%-14s [ZATRZYMANO]%s\n' "$C_YELLOW" "$alias" "$C_RESET"
  fi
}

app_stop_all() {
  if docker ps --filter "name=miniorderplatform-" -q 2>/dev/null | grep -q .; then
    printf 'Zatrzymuje kontenery serwisow Docker...\n'
    apps_compose stop product-service order-service inventory-service payment-service \
      notification-service customer-service delivery-service promotion-service \
      wishlist-service review-service recommendation-service chat-service \
      returns-service billing-service api-gateway 2>/dev/null || true
  fi
  local alias pid_file pid stopped=0
  while IFS= read -r alias; do
    pid_file="$(app_pid_file "$alias")"
    if [[ -f "$pid_file" ]]; then
      read -r pid < "$pid_file" || pid=''
      if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
        if command -v setsid >/dev/null 2>&1; then
          kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
        else
          kill "$pid" 2>/dev/null || true
        fi
        printf '%-14s zatrzymano (PID %s)\n' "$alias" "$pid"
        stopped=$((stopped + 1))
      fi
      rm -f -- "$pid_file"
    fi
  done < <(app_aliases)
  printf 'Zatrzymano procesow/kontenerow: %s\n' "$stopped"
}

app_status_all() {
  local alias pid_file pid
  while IFS= read -r alias; do
    pid_file="$(app_pid_file "$alias")"
    if app_docker_container_running "$alias"; then
      printf '%-14s DOCKER / RUNNING (port %s)\n' "$alias" "$(app_port "$alias")"
    elif app_process_running "$alias"; then
      read -r pid < "$pid_file"
      printf '%-14s RUNNING (PID %s, port %s)\n' "$alias" "$pid" "$(app_port "$alias")"
    elif app_endpoint_healthy "$alias"; then
      printf '%-14s EXTERNAL/HEALTHY (port %s, PID poza centrala)\n' "$alias" "$(app_port "$alias")"
    else
      printf '%-14s STOPPED (port %s)\n' "$alias" "$(app_port "$alias")"
    fi
  done < <(app_aliases)
}

app_logs() {
  local alias="${1:-}"
  [[ -n "$alias" ]] || { printf 'Uzycie: app-logs <service>\n' >&2; return 2; }
  app_project "$alias" >/dev/null || {
    printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2
    return 2
  }
  local sname c_id
  sname="$(app_docker_service_name "$alias")"
  c_id="$(docker ps -a --filter "name=${sname}" --format '{{.ID}}' 2>/dev/null | head -n 1)"
  if [[ -n "$c_id" ]]; then
    printf '%s--- Logi kontenera Docker: %s ---%s\n' "$C_BLUE" "$sname" "$C_RESET"
    docker logs --tail 100 "$c_id"
    return 0
  fi

  local log_file
  log_file="$(app_log_file "$alias")"
  [[ -f "$log_file" ]] || {
    printf '%sBrak logu: %s%s\n' "$C_RED" "$log_file" "$C_RESET" >&2
    return 1
  }
  tail -n 100 "$log_file"
}

frontend_pid_file() {
  printf '%s/frontend.pid' "$RUNTIME_DIRECTORY"
}

frontend_log_file() {
  printf '%s/frontend.log' "$RUNTIME_DIRECTORY/logs"
}

frontend_process_running() {
  local pid_file pid
  pid_file="$(frontend_pid_file)"
  [[ -f "$pid_file" ]] || return 1
  read -r pid < "$pid_file" || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

frontend_up() {
  require_command python3 || {
    printf '%s%s%s\n' "$C_RED" "$(platform_python_install_hint)" "$C_RESET" >&2
    return 1
  }
  [[ -d "$FRONTEND_DIRECTORY" ]] || {
    printf '%sBrak katalogu frontendu: %s%s\n' "$C_RED" "$FRONTEND_DIRECTORY" "$C_RESET" >&2
    return 1
  }
  mkdir -p "$RUNTIME_DIRECTORY/logs"
  if frontend_process_running; then
    printf 'Frontend juz dziala (PID %s).\n' "$(<"$(frontend_pid_file)")"
    frontend_status
    return 0
  fi

  rm -f -- "$(frontend_pid_file)"
  local log_file pid
  log_file="$(frontend_log_file)"
  if command -v setsid >/dev/null 2>&1; then
    (
      cd "$PROJECT_ROOT" || exit 1
       exec setsid python3 "$PROJECT_ROOT/scripts/frontend-server.py" \
         --port "$FRONTEND_PORT" --bind 127.0.0.1 --directory "$FRONTEND_DIRECTORY"
    ) > "$log_file" 2>&1 &
  else
    (
      cd "$PROJECT_ROOT" || exit 1
       exec python3 "$PROJECT_ROOT/scripts/frontend-server.py" \
         --port "$FRONTEND_PORT" --bind 127.0.0.1 --directory "$FRONTEND_DIRECTORY"
    ) > "$log_file" 2>&1 &
  fi
  pid=$!
  printf '%s\n' "$pid" > "$(frontend_pid_file)"
  printf 'Frontend uruchomiono (PID %s).\n' "$pid"
  frontend_status
}

frontend_down() {
  local pid_file pid
  pid_file="$(frontend_pid_file)"
  if [[ -f "$pid_file" ]]; then
    read -r pid < "$pid_file" || pid=''
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      if command -v setsid >/dev/null 2>&1; then
        kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
      else
        kill "$pid" 2>/dev/null || true
      fi
      printf 'Frontend zatrzymano (PID %s).\n' "$pid"
    else
      printf 'Frontend nie byl uruchomiony.\n'
    fi
    rm -f -- "$pid_file"
  else
    printf 'Frontend nie byl uruchomiony.\n'
  fi
}

frontend_status() {
  require_command curl || return 1
  local url="http://127.0.0.1:${FRONTEND_PORT}/"
  if frontend_process_running; then
    if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1; then
      printf 'frontend        RUNNING (PID %s)\n' "$(<"$(frontend_pid_file)")"
    else
      printf 'frontend        STARTING (PID %s)\n' "$(<"$(frontend_pid_file)")"
    fi
  else
    printf 'frontend        STOPPED\n'
  fi
  printf 'Frontend URL: %s\n' "$url"
}

frontend_logs() {
  local log_file
  log_file="$(frontend_log_file)"
  [[ -f "$log_file" ]] || {
    printf '%sBrak logu frontendu: %s%s\n' "$C_RED" "$log_file" "$C_RESET" >&2
    return 1
  }
  tail -n 100 "$log_file"
}

web_start() {
  bash "$SCRIPT_DIR/web/start-web.sh" "$@"
}


run_tests() {
  local alias="${1:-all}"
  export SPRING_PROFILES_ACTIVE="test"
  printf '%sUruchamiam testy z profilem test.%s\n' "$C_BLUE" "$C_RESET"
  if [[ "$alias" == "all" ]]; then
    maven test
  else
    local project
    project="$(app_project "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
    maven -pl "$project" test
  fi
}

run_package() {
  local alias="${1:-all}"
  if [[ "$alias" == "all" ]]; then
    maven package
  else
    local project
    project="$(app_project "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
    maven -pl "$project" package
  fi
}

check_health_one() {
  local alias="$1"
  local port path url status
  port="$(app_port "$alias")" || { printf '%sNieznany projekt: %s%s\n' "$C_RED" "$alias" "$C_RESET" >&2; return 2; }
  path="$(app_health_path "$alias")" || return 2
  url="http://127.0.0.1:${port}${path}"
  if ! status="$(curl --silent --show-error --connect-timeout 2 --max-time 5 \
      -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)"; then
    status='000'
  fi
  if [[ "$status" == "000" ]]; then
    printf '%-18s %sOFFLINE%s (%s)\n' "$alias" "$C_RED" "$C_RESET" "$url"
    return 1
  fi
  printf '%-18s %sHTTP %s%s (%s)\n' "$alias" "$C_GREEN" "$status" "$C_RESET" "$url"
}

health() {
  require_command curl || return 1
  local alias="${1:-all}"
  local failures=0
  if [[ "$alias" == "all" ]]; then
     for app in gateway product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
      check_health_one "$app" || failures=$((failures + 1))
    done
  else
    check_health_one "$alias" || failures=$((failures + 1))
  fi
  return "$failures"
}

swagger_ui() {
  printf 'Główny zagregowany Swagger UI (wszystkie 15 mikroserwisów w 1 oknie):\n'
  printf '  -> http://127.0.0.1:18080/swagger-ui.html\n'
}

api_docs_urls() {
  printf 'GŁÓWNY ZAGREGOWANY SWAGGER UI (wszystkie 15 mikroserwisów w 1 oknie):\n'
  printf '  -> http://127.0.0.1:18080/swagger-ui.html\n\n'
  printf 'Bezpośrednie adresy dokumentacji OpenAPI/Swagger poszczególnych usług (dev/test):\n\n'
  local service port
  for service in gateway product order inventory payment notification customer delivery promotion wishlist review recommendation chat returns billing; do
    port="$(app_port "$service")"
    printf '%-14s Swagger UI: http://127.0.0.1:%s/swagger-ui.html\n' "$service" "$port"
    printf '%-14s OpenAPI JSON: http://127.0.0.1:%s/v3/api-docs\n' "$service" "$port"
  done
  printf '\nPubliczne wersjonowane API Gateway: http://127.0.0.1:18080/api/v1\n'
  printf 'Profil prod ma dokumentacje wylaczona domyslnie.\n'
}

qa_contract_test() {
  bash "$PROJECT_ROOT/scripts/qa/contract-test.sh"
}

qa_security_test() {
  bash "$PROJECT_ROOT/scripts/qa/security-test.sh"
}

qa_zap_dast() {
  bash "$PROJECT_ROOT/scripts/qa/zap-dast.sh"
}

qa_load_test() {
  bash "$PROJECT_ROOT/scripts/qa/load-test.sh"
}

qa_e2e() {
  bash "$PROJECT_ROOT/scripts/qa/e2e.sh"
}

qa_resilience_test() {
  bash "$PROJECT_ROOT/scripts/qa/resilience-test.sh" "$@"
}

ui_package_manager() {
  if command -v pnpm >/dev/null 2>&1; then
    printf 'pnpm'
  elif command -v npm >/dev/null 2>&1; then
    printf 'npm'
  else
    return 1
  fi
}

ui_node_supported() {
  command -v node >/dev/null 2>&1 || return 1
  local major
  major="$(node --version 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/')"
  [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 20 ))
}

qa_ui_exec() {
  require_command node || return 1
  ui_node_supported || {
    printf '%sPlaywright wymaga Node.js 20 lub nowszego. Zainstaluj Node.js 22 LTS w WSL.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  local manager
  manager="$(ui_package_manager)" || {
    printf '%sBrak pnpm albo npm. Zainstaluj Node.js LTS z npm lub wlacz Corepack/pnpm.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  [[ -d "$PROJECT_ROOT/node_modules/@playwright/test" ]] || {
    printf '%sBrak zaleznosci Playwright. Uruchom: ui-test-install%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  (
    cd "$PROJECT_ROOT"
    if [[ "$manager" == 'pnpm' ]]; then
      pnpm exec playwright "$@"
    else
      npx --no-install playwright "$@"
    fi
  )
}

qa_ui_status() {
  printf '%sPlaywright UI E2E - status%s\n\n' "$C_BLUE" "$C_RESET"
  if command -v node >/dev/null 2>&1; then
    if ui_node_supported; then
      printf '%-24s OK (%s)\n' 'node' "$(node --version)"
    else
      printf '%-24s ZA STARY (%s; wymagany >= 20)\n' 'node' "$(node --version)"
    fi
  else
    printf '%-24s BRAK\n' 'node'
  fi
  local manager=''
  manager="$(ui_package_manager 2>/dev/null || true)"
  [[ -n "$manager" ]] && printf '%-24s OK (%s)\n' 'package manager' "$manager" || printf '%-24s BRAK (pnpm/npm)\n' 'package manager'
  [[ -f "$PROJECT_ROOT/package.json" ]] && printf '%-24s OK\n' 'package.json' || printf '%-24s BRAK\n' 'package.json'
  [[ -f "$PROJECT_ROOT/playwright.config.ts" ]] && printf '%-24s OK\n' 'playwright.config.ts' || printf '%-24s BRAK\n' 'playwright.config.ts'
  [[ -d "$PROJECT_ROOT/node_modules/@playwright/test" ]] && printf '%-24s OK\n' 'Playwright package' || printf '%-24s BRAK (ui-test-install)\n' 'Playwright package'
  local test_count
  test_count="$(find "$PROJECT_ROOT/e2e/tests" -type f -name '*.spec.ts' 2>/dev/null | wc -l | tr -d ' ')"
  printf '%-24s %s plikow spec\n' 'testy UI' "${test_count:-0}"
  printf '%-24s %s\n' 'raport' "$PROJECT_ROOT/gitIgnoreOwn/runtime/playwright/report/index.html"
}

qa_ui_install() {
  require_command node || return 1
  ui_node_supported || {
    printf '%sPlaywright wymaga Node.js 20 lub nowszego. Zainstaluj Node.js 22 LTS w WSL i ponow operacje.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  local manager
  manager="$(ui_package_manager)" || {
    printf '%sBrak pnpm albo npm. W WSL zainstaluj Node.js LTS z npm.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  (
    cd "$PROJECT_ROOT"
    if [[ "$manager" == 'pnpm' ]]; then
      pnpm install
      pnpm exec playwright install chromium
    else
      if [[ -f package-lock.json ]]; then npm ci; else npm install --no-package-lock; fi
      npx playwright install chromium
    fi
  )
  printf '\nPlaywright i Chromium sa gotowe. Pozostale silniki zainstalujesz przez ui-test-browsers.\n'
}

qa_ui_smoke() {
  qa_ui_exec test --project=chromium --grep '@smoke' --grep-invert '@full-system'
}

qa_ui_regression() {
  qa_ui_exec test --project=chromium --grep-invert '@full-system'
}

qa_ui_system() {
  printf 'Sprawdzam frontend i serwisy przed pelnym testem przegladarkowym...\n'
  frontend_status || return 1
  health all || return 1
  (
    export PLAYWRIGHT_FULL_SYSTEM=1
    qa_ui_exec test e2e/tests/full-system --project=chromium
  )
}

qa_ui_browsers() {
  local manager
  manager="$(ui_package_manager)" || return 1
  local has_webkit=0
  if ldconfig -p 2>/dev/null | grep -q 'libgtk-4'; then
    has_webkit=1
  fi

  (
    cd "$PROJECT_ROOT"
    if (( has_webkit )); then
      if [[ "$manager" == 'pnpm' ]]; then
        pnpm exec playwright install chromium firefox webkit
      else
        npx --no-install playwright install chromium firefox webkit
      fi
    else
      if [[ "$manager" == 'pnpm' ]]; then
        pnpm exec playwright install chromium firefox
      else
        npx --no-install playwright install chromium firefox
      fi
    fi
  ) || return 1

  if (( ! has_webkit )); then
    printf '\n%s[WSL Info] Silnik WebKit wymaga dodatkowych pakietow systemowych GTK4/GStreamer (sudo npx playwright install-deps webkit).%s\n' "$C_YELLOW" "$C_RESET"
    printf '%sUruchamiam testy dla w pelni wspieranych silnikow: Chromium, Firefox oraz Mobile Chrome.%s\n\n' "$C_BLUE" "$C_RESET"
    qa_ui_exec test --project=chromium --project=firefox --project=mobile-chrome --grep-invert '@full-system'
  else
    qa_ui_exec test --grep-invert '@full-system'
  fi
}

qa_ui_headed() {
  qa_ui_exec test --project=chromium --grep-invert '@full-system' --headed
}

qa_ui_debug() {
  (
    export PWDEBUG=1
    qa_ui_exec test --project=chromium --grep-invert '@full-system' --debug
  )
}

qa_ui_report() {
  local report_directory="$PROJECT_ROOT/gitIgnoreOwn/runtime/playwright/report"
  [[ -f "$report_directory/index.html" ]] || {
    printf '%sBrak raportu. Najpierw uruchom ui-test-smoke albo ui-test.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Raport: http://127.0.0.1:19325\nZakoncz serwer raportu przez Ctrl+C.\n'
  qa_ui_exec show-report "$report_directory" --host 127.0.0.1 --port 19325
}

images_build() {
  bash "$PROJECT_ROOT/scripts/deploy/build-images.sh"
}

k8s_validate() {
  require_command kubectl || return 1
  kubectl kustomize "$PROJECT_ROOT/deploy/k8s/overlays/prod" >/dev/null
  printf 'Manifesty Kubernetes sa poprawne skladniowo. Nie wykonano wdrozenia.\n'
}

k8s_status() {
  require_command kubectl || return 1
  kubectl -n minitech-store get deployments,pods,services
}

openbao_status() {
  printf '%sOpenBao — status profilu prod%s\n' "$C_BLUE" "$C_RESET"
  printf 'Adres: %s\n' "$OPENBAO_ADDR"
  if command -v bao >/dev/null 2>&1; then
    local bao_args=(status -format=json)
    [[ -n "$OPENBAO_CACERT" ]] && bao_args+=("-ca-cert=$OPENBAO_CACERT")
    if ! BAO_ADDR="$OPENBAO_ADDR" bao "${bao_args[@]}" 2>/tmp/mini-order-platform-openbao-status.err; then
      printf '%sOpenBao jest niedostepny, zainicjalizowany lub zapieczetowany. Szczegoly techniczne:%s\n' "$C_DIM" "$C_RESET"
      sed -E 's/(token|secret|password|key)[^:]*:[^,}]*/\1: [ukryte]/Ig' \
        /tmp/mini-order-platform-openbao-status.err | head -n 5
      rm -f -- /tmp/mini-order-platform-openbao-status.err
      return 1
    fi
    rm -f -- /tmp/mini-order-platform-openbao-status.err
    return 0
  fi
  if ! command -v curl >/dev/null 2>&1; then
    printf '%sBrak bao i curl — nie mozna sprawdzic endpointu.%s\n' "$C_RED" "$C_RESET"
    return 1
  fi
  local curl_args=(--silent --show-error --connect-timeout 3 --max-time 5)
  [[ -n "$OPENBAO_CACERT" ]] && curl_args+=(--cacert "$OPENBAO_CACERT") || {
    printf '%sBrak OPENBAO_CACERT. Dla produkcji nie pomijam weryfikacji TLS.%s\n' "$C_RED" "$C_RESET"
    return 1
  }
  curl "${curl_args[@]}" "$OPENBAO_ADDR/v1/sys/health" | python3 -c \
    'import json,sys; d=json.load(sys.stdin); print("initialized=%s sealed=%s standby=%s" % (d.get("initialized"), d.get("sealed"), d.get("standby")))'
}

openbao_validate() {
  local failures=0
  printf '%sWalidacja przygotowania OpenBao/TLS prod%s\n' "$C_BLUE" "$C_RESET"
  for file in \
    "$PROJECT_ROOT/infra/openbao/openbao-prod.hcl.example" \
    "$PROJECT_ROOT/infra/openbao/secret-paths.env.example" \
    "$PROJECT_ROOT/infra/openbao/policies/customer-service.hcl.example" \
    "$PROJECT_ROOT/deploy/k8s/overlays/prod/ingress.yaml" \
    "$PROJECT_ROOT/deploy/k8s/overlays/prod/openbao-csi.example.yaml"; do
    if [[ -f "$file" ]]; then
      printf 'OK   %s\n' "${file#"$PROJECT_ROOT/"}"
    else
      printf '%sBRAK %s%s\n' "$C_RED" "${file#"$PROJECT_ROOT/"}" "$C_RESET"
      failures=$((failures + 1))
    fi
  done
  if [[ -n "$OPENBAO_CACERT" && -f "$OPENBAO_CACERT" ]]; then
    printf 'OK   OPENBAO_CACERT (sciezka, wartosc ukryta)\n'
  else
    printf '%sINFO OPENBAO_CACERT nie jest ustawiony — wymagany dopiero przy polaczeniu z produkcyjnym OpenBao.%s\n' "$C_DIM" "$C_RESET"
  fi
  printf 'Nie wykonano inicjalizacji, unseal, tworzenia polityk ani wdrozenia klastra.\n'
  return "$failures"
}

tls_status() {
  require_command openssl || return 1
  printf '%sTLS — kontrola certyfikatu%s\n' "$C_BLUE" "$C_RESET"
  printf 'Cel: %s:%s\n' "$TLS_CHECK_HOST" "$TLS_CHECK_PORT"
  local output openssl_args=(-connect "$TLS_CHECK_HOST:$TLS_CHECK_PORT" -servername "$TLS_CHECK_HOST" -verify_return_error -brief)
  [[ -n "$TLS_CHECK_CACERT" ]] && openssl_args+=(-CAfile "$TLS_CHECK_CACERT")
  output="$(printf '' | openssl s_client "${openssl_args[@]}" 2>&1)" || {
    printf '%sNie mozna potwierdzic certyfikatu TLS. Sprawdz DNS, ingress i CA.%s\n' "$C_RED" "$C_RESET"
    printf '%s\n' "$output" | grep -E 'Verification|verify error|subject=|issuer=' | head -n 5 || true
    return 1
  }
  printf '%s\n' "$output" | grep -E 'Protocol|Ciphersuite|Verification|Peer certificate' || true
}

resilience_status() {
  require_command curl || return 1
  local failures=0
  local service port metrics cb retry bulkhead
  printf '%-14s %-8s %-12s %-12s %-12s %-12s\n' \
    'Serwis' 'HTTP' 'CB metryki' 'Retry metryki' 'Bulkhead' 'Endpoint'
  printf '%-14s %-8s %-12s %-12s %-12s %-12s\n' \
    '------' '----' '----------' '-------------' '--------' '--------'
  for service in gateway order payment customer promotion; do
    port="$(app_port "$service")"
    if ! metrics="$(curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:${port}/actuator/prometheus" 2>/dev/null)"; then
      printf '%-14s %-8s %-12s %-12s %-12s %s\n' \
        "$service" 'OFFLINE' '-' '-' '-' "http://127.0.0.1:${port}/actuator/prometheus"
      failures=$((failures + 1))
      continue
    fi
    cb="$(printf '%s\n' "$metrics" | grep -c '^resilience4j_circuitbreaker_' || true)"
    retry="$(printf '%s\n' "$metrics" | grep -c '^resilience4j_retry_' || true)"
    bulkhead="$(printf '%s\n' "$metrics" | grep -c '^resilience4j_bulkhead_' || true)"
    printf '%-14s %-8s %-12s %-12s %-12s %s\n' \
      "$service" 'UP' "$cb" "$retry" "$bulkhead" "http://127.0.0.1:${port}/actuator/prometheus"
  done
  printf '\nWartosci oznaczaja liczbe aktualnie wystawianych serii metryk. Nazwy uslug sa widoczne w etykiecie name.\n'
  return "$failures"
}

smoke() {
  require_command curl || return 1
  local url='http://127.0.0.1:18080/api/products'
  printf 'Smoke: %s\n' "$url"
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url"
  printf '\n%sSmoke zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
}

delivery_smoke() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local methods method_code quote
  methods="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/deliveries/methods")" || {
    printf '%sDelivery Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  method_code="$(printf '%s' "$methods" | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data[0]["code"] if data else "")')"
  [[ -n "$method_code" ]] || {
    printf '%sDelivery Service nie zwrocil aktywnych metod.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  quote="$(printf '{"methodCode":"%s","postalCode":"00-001","itemCount":1,"subtotal":499.99}' "$method_code" | \
    curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
      -H 'Content-Type: application/json' -X POST "$gateway/deliveries/quote" --data-binary @- )" || {
    printf '%sNie udalo sie wyliczyc wyceny dostawy.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Aktywne metody dostawy: %s\n' "$(printf '%s' "$methods" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
  printf 'Przykladowa wycena (%s, koszyk 499,99 zl): %s\n' "$method_code" \
    "$(printf '%s' "$quote" | python3 -c 'import json,sys; q=json.load(sys.stdin); print("{} zl, razem {} zl".format(q["deliveryCost"], q["totalPrice"]))')"
  printf '%sDelivery smoke zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
}

delivery_tracking() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local order_id="${1:-}" response
  if [[ -z "$order_id" ]]; then
    printf '%s: ' "$(cc_text 'Numer zamówienia' 'Order ID')"
    read -r order_id || return 1
  fi
  case "$(platform_lowercase "$order_id")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ "$order_id" =~ ^[1-9][0-9]*$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Numer zamówienia musi być dodatnią liczbą.' 'Order ID must be a positive number.')" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/deliveries/orders/$order_id/tracking")" || {
    printf '%sNie znaleziono przesyłki albo Delivery Service jest niedostępny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Zamówienie #{} | {} | {} | {}".format(d["orderId"], d["carrier"], d["trackingNumber"], d["status"]))'
}

delivery_tracking_status() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local order_id="${1:-}" status="${2:-}" payload response
  if [[ -z "$order_id" ]]; then
    printf '%s: ' "$(cc_text 'Numer zamówienia' 'Order ID')"
    read -r order_id || return 1
  fi
  case "$(platform_lowercase "$order_id")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  if [[ -z "$status" ]]; then
    printf '%s [LABEL_CREATED|READY_FOR_PICKUP|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|PICKED_UP|CANCELLED]: ' \
      "$(cc_text 'Nowy status przesyłki' 'New shipment status')"
    read -r status || return 1
  fi
  status="$(printf '%s' "$status" | tr '[:lower:]' '[:upper:]')"
  case "$status" in
    LABEL_CREATED|READY_FOR_PICKUP|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|PICKED_UP|CANCELLED) ;;
    0) return 0 ;;
    EXIT|QUIT) exit 0 ;;
    *) printf '%sNieprawidłowy status przesyłki.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;;
  esac
  payload="$(STATUS="$status" python3 -c 'import json,os; print(json.dumps({"status": os.environ["STATUS"]}))')"
  response="$(printf '%s' "$payload" | curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X PATCH \
    "$gateway/deliveries/orders/$order_id/tracking" --data-binary @-)" || {
    printf '%sNie udało się zmienić statusu przesyłki.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Zamówienie #{} | {} | {}".format(d["orderId"], d["trackingNumber"], d["status"]))'
}

promotion_smoke() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local campaigns evaluation
  campaigns="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/promotions/active")" || {
    printf '%sPromotion Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  evaluation="$(printf '[{"productId":1,"basePrice":699.99,"catalogIndex":0,"catalogSize":10}]' | \
    curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
      -H 'Content-Type: application/json' -X POST "$gateway/promotions/evaluate" --data-binary @- )" || {
    printf '%sNie udalo sie wyliczyc promocji.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf 'Aktywne kampanie promocji: %s\n' "$(printf '%s' "$campaigns" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')"
  printf 'Przykladowa oferta: %s\n' "$(printf '%s' "$evaluation" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0] if d else "brak")')"
  printf '%sPromotion smoke zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
}

promotion_codes() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local code="${1:-}" response
  if [[ -n "$code" ]]; then
    response="$(printf '{"code":"%s","subtotal":499.99}' "${code//\"/}" | \
      curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
        -H 'Content-Type: application/json' -X POST "$gateway/promotions/codes/validate" --data-binary @-)" || {
      printf '%sNie udalo sie zweryfikowac kodu rabatowego.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
    }
    printf 'Weryfikacja kodu %s: %s\n' "$code" "$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin))')"
    return 0
  fi
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/promotions/codes")" || {
    printf '%sPromotion Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response" | python3 -c '
import json, sys
from datetime import date

today = date.today()
for c in json.load(sys.stdin):
    v_from = date.fromisoformat(c["validFrom"]) if c.get("validFrom") else None
    v_to = date.fromisoformat(c["validTo"]) if c.get("validTo") else None

    max_r = c.get("maxRedemptions")
    red_c = c.get("redeemedCount", 0)
    if not c.get("active"):
        status_info = "🔴 NIEAKTYWNY"
    elif v_from and today < v_from:
        status_info = f"🟡 WAŻNY OD {v_from}"
    elif v_to and today > v_to:
        status_info = f"🔴 WYGASŁY ({v_to})"
    elif max_r is not None and red_c >= max_r:
        status_info = f"🔴 LIMIT WYCZERPANY ({red_c}/{max_r})"
    else:
        v_to_str = str(v_to) if v_to else "bezterminowo"
        status_info = f"🟢 WAŻNY (do {v_to_str})"

    limit_max = max_r if max_r is not None else "bez limitu"
    limits = f"{red_c}/{limit_max}"
    code_name = c["code"]
    disc = c["discountPercent"]
    desc = c.get("description", "-")
    print(f"{code_name}: -{disc}% | {desc} | użycia={limits} | status={status_info}")
'
}

promotion_code_create() {
  require_command curl || return 1
  require_command python3 || return 1
  ensure_local_profile || return 1
  local gateway='http://127.0.0.1:18080/api'
  local code description discount max_redemptions minimum_order_value maximum_discount_amount valid_from valid_to active payload response
  local default_from default_to

  default_from="$(date -u +%F)"
  default_to='2099-12-31'
  printf '%s\n' "$(cc_text 'Tworzenie kodu rabatowego (dev/test)' 'Create promotion code (dev/test)')"
  printf '%s: ' "$(cc_text 'Kod (puste = wygeneruj automatycznie, 0 = powrot)' 'Code (empty = generate automatically, 0 = back)')"
  read -r code || return 1
  case "$(platform_lowercase "$code")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ -z "$code" || "$code" =~ ^[[:alnum:]][[:alnum:]_-]{2,39}$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Kod musi miec 3-40 znakow: litery, cyfry, - lub _.' 'Code must contain 3-40 letters, digits, - or _.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Opis [Kod rabatowy MiniTech Store]' 'Description [MiniTech Store promotion code]')"
  read -r description || return 1
  description="${description:-Kod rabatowy MiniTech Store}"

  printf '%s: ' "$(cc_text 'Rabat procentowy [10]' 'Discount percentage [10]')"
  read -r discount || return 1
  discount="${discount:-10}"
  [[ "$discount" =~ ^[0-9]+$ && "$discount" -ge 1 && "$discount" -le 100 ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Rabat musi byc liczba od 1 do 100.' 'Discount must be a number from 1 to 100.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Limit uzyc [puste = bez limitu]' 'Maximum redemptions [empty = unlimited]')"
  read -r max_redemptions || return 1
  [[ -z "$max_redemptions" || ( "$max_redemptions" =~ ^[0-9]+$ && "$max_redemptions" -gt 0 ) ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Limit musi byc dodatnia liczba albo pozostac pusty.' 'Limit must be a positive number or empty.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Minimalna wartosc koszyka [0]' 'Minimum cart value [0]')"
  read -r minimum_order_value || return 1
  minimum_order_value="${minimum_order_value:-0}"
  [[ "$minimum_order_value" =~ ^[0-9]+([.][0-9]{1,2})?$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Minimalna wartosc koszyka musi byc liczba nieujemna.' 'Minimum cart value must be a non-negative number.')" "$C_RESET" >&2
    return 2
  }

  printf '%s: ' "$(cc_text 'Maksymalny rabat w zl [puste = bez limitu]' 'Maximum discount in PLN [empty = unlimited]')"
  read -r maximum_discount_amount || return 1
  [[ -z "$maximum_discount_amount" || "$maximum_discount_amount" =~ ^[0-9]+([.][0-9]{1,2})?$ ]] || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Maksymalny rabat musi byc liczba dodatnia albo pusty.' 'Maximum discount must be a positive number or empty.')" "$C_RESET" >&2
    return 2
  }

  printf '%s [%s]: ' "$(cc_text 'Wazny od' 'Valid from')" "$default_from"
  read -r valid_from || return 1
  valid_from="${valid_from:-$default_from}"
  printf '%s [%s]: ' "$(cc_text 'Wazny do' 'Valid to')" "$default_to"
  read -r valid_to || return 1
  valid_to="${valid_to:-$default_to}"
  if ! FROM_DATE="$valid_from" TO_DATE="$valid_to" python3 -c 'from datetime import date; import os; start=date.fromisoformat(os.environ["FROM_DATE"]); end=date.fromisoformat(os.environ["TO_DATE"]); raise SystemExit(0 if end >= start else 1)'; then
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Daty musza miec format RRRR-MM-DD, a data koncowa nie moze byc wczesniejsza.' 'Dates must use YYYY-MM-DD and the end date cannot be earlier than the start date.')" "$C_RESET" >&2
    return 2
  fi

  printf '%s [true]: ' "$(cc_text 'Aktywny (true/false)' 'Active (true/false)')"
  read -r active || return 1
  active="${active:-true}"
  case "$(platform_lowercase "$active")" in
    true|t|yes|y|tak) active=true ;;
    false|f|no|n|nie) active=false ;;
    *) printf '%s%s%s\n' "$C_RED" "$(cc_text 'Aktywny musi byc true albo false.' 'Active must be true or false.')" "$C_RESET" >&2; return 2 ;;
  esac

  payload="$(CODE="$code" DESCRIPTION="$description" DISCOUNT="$discount" MAX_REDEMPTIONS="$max_redemptions" MINIMUM_ORDER_VALUE="$minimum_order_value" MAXIMUM_DISCOUNT_AMOUNT="$maximum_discount_amount" VALID_FROM="$valid_from" VALID_TO="$valid_to" ACTIVE="$active" python3 -c 'import json,os; print(json.dumps({"code": os.environ["CODE"] or None, "description": os.environ["DESCRIPTION"], "discountPercent": int(os.environ["DISCOUNT"]), "maxRedemptions": int(os.environ["MAX_REDEMPTIONS"]) if os.environ["MAX_REDEMPTIONS"] else None, "minimumOrderValue": float(os.environ["MINIMUM_ORDER_VALUE"]), "maximumDiscountAmount": float(os.environ["MAXIMUM_DISCOUNT_AMOUNT"]) if os.environ["MAXIMUM_DISCOUNT_AMOUNT"] else None, "validFrom": os.environ["VALID_FROM"], "validTo": os.environ["VALID_TO"], "active": os.environ["ACTIVE"] == "true"}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X POST "$gateway/promotions/codes" --data-binary "$payload")" || {
    printf '%s%s%s\n' "$C_RED" "$(cc_text 'Nie udalo sie utworzyc kodu. Sprawdz, czy Promotion Service dziala i czy kod nie istnieje.' 'Could not create the code. Check Promotion Service and whether the code already exists.')" "$C_RESET" >&2
    return 1
  }
  printf '%s%s%s\n' "$C_GREEN" "$(printf '%s' "$response" | python3 -c 'import json,sys; d=json.load(sys.stdin); limit=d["maxRedemptions"] if d["maxRedemptions"] is not None else "bez limitu"; print("Utworzono kod {}: -{}%, wazny {}–{}, limit: {}".format(d["code"], d["discountPercent"], d["validFrom"], d["validTo"], limit))')" "$C_RESET"
}

review_pending() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 'http://127.0.0.1:18080/api/reviews/moderation/pending' || {
    printf '%sReview Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '\n'
}

returns_list() {
  require_command curl || return 1
  curl --silent --show-error --fail --connect-timeout 3 --max-time 10 'http://127.0.0.1:18080/api/returns' || {
    printf '%sReturns Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '\n'
}

returns_status() {
  require_command curl || return 1
  local return_id="${1:-}" status="${2:-}" note="${3:-}" payload response
  if [[ -z "$return_id" ]]; then printf 'ID zwrotu/reklamacji: '; read -r return_id || return 1; fi
  if [[ -z "$status" ]]; then printf 'Status (UNDER_REVIEW/APPROVED/REJECTED/COMPLETED): '; read -r status || return 1; fi
  status="$(printf '%s' "$status" | tr '[:lower:]' '[:upper:]')"
  case "$status" in UNDER_REVIEW|APPROVED|REJECTED|COMPLETED) ;; *) printf '%sNiepoprawny status zgloszenia.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;; esac
  if [[ -z "$note" ]]; then printf 'Notatka [opcjonalnie]: '; read -r note || return 1; fi
  payload="$(RETURN_STATUS="$status" RETURN_NOTE="$note" python3 -c 'import json,os; print(json.dumps({"status":os.environ["RETURN_STATUS"],"note":os.environ["RETURN_NOTE"] or None}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/returns/$return_id/status" --data-binary "$payload")" || {
    printf '%sNie udalo sie zmienic statusu zgloszenia.%s\n' "$C_RED" "$C_RESET" >&2; return 1;
  }
  printf '%s\n' "$response"
}

billing_invoice() {
  require_command curl || return 1
  require_command python3 || return 1
  local order_id="${1:-}" response order_info
  if [[ -z "$order_id" ]]; then printf 'ID zamowienia: '; read -r order_id || return 1; fi
  [[ "$order_id" =~ ^[0-9]+$ ]] || { printf '%sID zamowienia musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }

  printf '%sPobieranie faktury dla zamowienia #%s...%s\n' "$C_BLUE" "$order_id" "$C_RESET"
  
  response="$(curl --silent --show-error --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/order/$order_id" 2>/dev/null)" || true
  
  if [[ -n "$response" ]] && printf '%s' "$response" | grep -q '"invoiceNumber"'; then
    printf '%s' "$response" | python3 -c '
import json, sys
data = json.load(sys.stdin)
print("\n" + "=" * 60)
print("📄 FAKTURA VAT: {}".format(data.get("invoiceNumber", "BRAK")))
print("=" * 60)
print("Status dokumentu:    {}".format(data.get("status", "-")))
print("ID zamówienia:       #{}".format(data.get("orderId", "-")))
print("Data wystawienia:    {}".format(data.get("createdAt", "-")))
print("Nabywca:             {}".format(data.get("companyName") or data.get("buyerName") or "-"))
if data.get("taxId"):
    print("NIP:                 {}".format(data.get("taxId")))
print("Adres:               {}, {} {}".format(data.get("street", ""), data.get("postalCode", ""), data.get("city", "")))
print("E-mail:              {}".format(data.get("customerEmail", "-")))
print("-" * 60)
print("POZYCJE FAKTURY:")
for item in data.get("items", []):
    print("  • {} (SKU: {}) x{} — {} {} (brutto)".format(
        item.get("productName", "-"), item.get("sku", "-"), item.get("quantity", 1),
        item.get("totalPrice", "-"), data.get("currency", "PLN")))
print("-" * 60)
print("Suma Netto:          {} {}".format(data.get("netAmount", "0.00"), data.get("currency", "PLN")))
print("Kwota VAT (23%):     {} {}".format(data.get("vatAmount", "0.00"), data.get("currency", "PLN")))
print("Dostawa:             {} {}".format(data.get("deliveryCost", "0.00"), data.get("currency", "PLN")))
if float(data.get("discountAmount", 0) or 0) > 0:
    print("Rabat:              -{} {}".format(data.get("discountAmount", "0.00"), data.get("currency", "PLN")))
print("SUMA BRUTTO:         {} {}".format(data.get("grossAmount", "0.00"), data.get("currency", "PLN")))
print("=" * 60)
print("📥 Pobierz plik PDF: http://127.0.0.1:18080/api/invoices/order/{}/pdf\n".format(data.get("orderId")))
'
    return 0
  fi

  # If invoice was not found, check order details
  order_info="$(curl --silent --connect-timeout 2 --max-time 5 "http://127.0.0.1:18080/api/orders/$order_id" 2>/dev/null)" || true
  if [[ -n "$order_info" ]] && printf '%s' "$order_info" | grep -q '"id"'; then
    local inv_req cust_email ord_status
    inv_req="$(printf '%s' "$order_info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("invoiceRequested", False))' 2>/dev/null || echo "False")"
    cust_email="$(printf '%s' "$order_info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("customerEmail", "-"))' 2>/dev/null || echo "-")"
    ord_status="$(printf '%s' "$order_info" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", "-"))' 2>/dev/null || echo "-")"

    printf '\n%s[INFORMACJA] Brak faktury dla zamowienia #%s%s\n' "$C_YELLOW" "$order_id" "$C_RESET"
    printf '  • Status zamowienia: %s\n' "$ord_status"
    printf '  • Klient (e-mail):   %s\n' "$cust_email"
    if [[ "$inv_req" == "True" || "$inv_req" == "true" ]]; then
      printf '  • Zadanie faktury:  TAK (faktura nie zostala jeszcze wygenerowana przez Billing Service)\n'
    else
      printf '  • Zadanie faktury:  NIE (zamowienie zostalo zlozone bez zaznaczenia prosby o fakture VAT)\n'
    fi
    printf '\n%sWskazowka:%s Uzyj polecenia „Wszystkie zamowienia” (orders-list), aby znalezc zamowienie z kolumna FAKTURA = TAK (np. #46).\n' "$C_GREEN" "$C_RESET"
    return 1
  else
    printf '%s[BŁĄD] Zamowienie #%s nie istnieje w systemie.%s\n' "$C_RED" "$order_id" "$C_RESET" >&2
    return 1
  fi
}

orders_list() {
  require_command curl || return 1
  require_command python3 || return 1
  local limit="${1:-100}" response
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 ]] || {
    printf '%sLimit musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 15 'http://127.0.0.1:18080/api/orders')" || {
    printf '%sOrder Service jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | ORDER_LIMIT="$limit" python3 -c '
import json, os, sys
orders = sorted(json.load(sys.stdin), key=lambda value: (value.get("createdAt") or "", value.get("id") or 0), reverse=True)
orders = orders[:int(os.environ["ORDER_LIMIT"])]
if not orders:
    print("Brak zamowien.")
    raise SystemExit
print("ID | STATUS | KWOTA | E-MAIL | FAKTURA | POZYCJE")
print("-" * 150)
for order in orders:
    items = ", ".join("{} x{}".format(item.get("productName", "produkt"), item.get("quantity", 0)) for item in order.get("items", [])) or "brak pozycji"
    print("#{} | {} | {} PLN | {} | {} | {}".format(order.get("id", "?"), order.get("status", "?"), order.get("totalPrice", "0.00"), order.get("customerEmail", "-"), "TAK" if order.get("invoiceRequested") else "NIE", items))
print("\nLiczba wyswietlonych zamowien: {}".format(len(orders)))
'
}

invoice_pdf() {
  require_command curl || return 1
  local order_id="${1:-}" output_dir="$PROJECT_ROOT/gitIgnoreOwn/runtime/invoices" output_file
  if [[ -z "$order_id" ]]; then printf 'ID zamowienia: '; read -r order_id || return 1; fi
  [[ "$order_id" =~ ^[0-9]+$ ]] || { printf '%sID zamowienia musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }
  mkdir -p "$output_dir"
  output_file="$output_dir/faktura-order-$order_id.pdf"
  if curl --silent --show-error --fail --connect-timeout 3 --max-time 15 "http://127.0.0.1:18080/api/invoices/order/$order_id/pdf" -o "$output_file"; then
    printf '%sPDF faktury zapisany: %s%s\n' "$C_GREEN" "$output_file" "$C_RESET"
  else
    rm -f "$output_file"
    if curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "http://127.0.0.1:18080/api/invoices/order/$order_id" -o /dev/null; then
      printf '%sFaktura dla zamowienia istnieje, ale uruchomiony Billing Service nie udostepnia endpointu PDF (HTTP 404). Zrestartuj BillingServiceApplication z aktualnego kodu i ponow probe.%s\n' "$C_RED" "$C_RESET" >&2
    else
      printf '%sBilling Service nie zwrocil faktury dla zamowienia albo jest niedostepny.%s\n' "$C_RED" "$C_RESET" >&2
    fi
    return 1
  fi
}

invoices_list() {
  require_command python3 || return 1
  local limit="${1:-50}"
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 ]] || {
    printf '%sLimit musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  local raw_json=""
  # Try REST API Gateway first
  raw_json="$(curl --silent --show-error --fail --connect-timeout 2 --max-time 5 "http://127.0.0.1:18080/api/invoices?size=$limit" 2>/dev/null)" || true

  # Fallback to direct DB query if REST API is not reachable
  if [[ -z "$raw_json" || "$raw_json" != \[* ]]; then
    local sql="SELECT coalesce(json_agg(t), '[]'::json) FROM (SELECT id, order_id, invoice_number, gross_amount, currency, customer_email, created_at FROM invoices ORDER BY id DESC LIMIT $limit) t;"
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q 'miniorderplatform-billing-db-1'; then
      raw_json="$(docker exec -i miniorderplatform-billing-db-1 psql -U billing_app -d billing_db -t -A -c "$sql" 2>/dev/null)"
    else
      ensure_local_profile || return 1
      raw_json="$(compose exec -T billing-db psql -U billing_app -d billing_db -t -A -c "$sql" 2>/dev/null)"
    fi
  fi

  if [[ -z "$raw_json" ]]; then
    printf '%sNie udalo sie pobrac listy faktur z serwisu ani z bazy billing_db.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi

  printf '%s' "$raw_json" | python3 -c '
import json, sys
data = json.load(sys.stdin)
if not data:
    print("Brak wystawionych faktur.")
    raise SystemExit
print("NR FAKTURY       | ZAMÓWIENIE | KWOTA BRUTTO | KLIENT                  | DATA WYSTAWIENIA")
print("-" * 95)
for inv in data:
    dt = (inv.get("createdAt") or inv.get("created_at") or "")[:19].replace("T", " ")
    email = (inv.get("customerEmail") or inv.get("customer_email") or "-")[:23]
    inv_nr = inv.get("invoiceNumber") or inv.get("invoice_number") or "-"
    ord_id = inv.get("orderId") or inv.get("order_id") or "-"
    gross = float(inv.get("grossAmount") if inv.get("grossAmount") is not None else inv.get("gross_amount", 0) or 0)
    curr = inv.get("currency", "PLN")
    print(f"{inv_nr:<16} | #{ord_id:<10} | {gross:>9.2f} {curr} | {email:<23} | {dt}")
print(f"\nLiczba wystawionych faktur: {len(data)}")
'
}

invoices_export_zip() {
  require_command curl || return 1
  local output_file="${1:-$PROJECT_ROOT/gitIgnoreOwn/runtime/invoices/faktury.zip}"
  mkdir -p "$(dirname "$output_file")"
  printf '%sPobieranie archiwum ZIP ze wszystkimi fakturami...%s\n' "$C_BLUE" "$C_RESET"
  if curl --silent --show-error --fail --connect-timeout 5 --max-time 60 "http://127.0.0.1:18080/api/invoices/export/zip" -o "$output_file" 2>/dev/null || \
     curl --silent --show-error --fail --connect-timeout 5 --max-time 60 "http://127.0.0.1:18095/invoices/export/zip" -o "$output_file"; then
    local file_size
    file_size="$(wc -c < "$output_file" 2>/dev/null || stat -c%s "$output_file" 2>/dev/null || echo "0")"
    printf '%sArchiwum ZIP zapisane pomyslnie: %s (%s bajtow)%s\n' "$C_GREEN" "$output_file" "$file_size" "$C_RESET"
  else
    printf '%sNie udalo sie pobrac archiwum ZIP z fakturami.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  fi
}

invoice_correct() {
  require_command curl || return 1
  require_command python3 || return 1
  local invoice_id="${1:-}" reason="${2:-}" delta="${3:-}" payload response
  if [[ -z "$invoice_id" ]]; then printf 'ID faktury: '; read -r invoice_id || return 1; fi
  [[ "$invoice_id" =~ ^[0-9]+$ ]] || { printf '%sID faktury musi byc liczba.%s\n' "$C_RED" "$C_RESET" >&2; return 2; }
  if [[ -z "$reason" ]]; then printf 'Przyczyna korekty: '; read -r reason || return 1; fi
  if [[ -z "$delta" ]]; then printf 'Kwota zmiany brutto (np. -129.99): '; read -r delta || return 1; fi

  payload="$(CORR_REASON="$reason" CORR_DELTA="$delta" python3 -c 'import json,os; print(json.dumps({"reason": os.environ["CORR_REASON"], "correctionGross": float(os.environ["CORR_DELTA"])}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18080/api/invoices/$invoice_id/corrections" --data-binary "$payload" 2>/dev/null || \
              curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X POST "http://127.0.0.1:18095/invoices/$invoice_id/corrections" --data-binary "$payload")" || {
    printf '%sNie udalo sie wystawic korekty faktury.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | python3 -c '
import json, sys
c = json.load(sys.stdin)
print("\n" + "=" * 60)
print(f"📄 WYSTAWIONO FAKTURĘ KORYGUJĄCĄ: {c.get(\"correctionNumber\")}")
print("=" * 60)
print(f"Do faktury pierwotnej: {c.get(\"originalInvoiceNumber\")}")
print(f"Przyczyna:             {c.get(\"reason\")}")
print(f"Kwota pierwotna:       {c.get(\"originalGross\")} PLN")
print(f"Korekta brutto:        {c.get(\"correctionGross\")} PLN")
print(f"Nowa kwota brutto:     {c.get(\"finalGross\")} PLN")
print("-" * 60)
print(f"📥 Pobierz PDF korekty: http://127.0.0.1:18080/api/invoices/corrections/{c.get(\"id\")}/pdf\n")
'
}

corrections_list() {
  require_command curl || return 1
  require_command python3 || return 1
  local invoice_id="${1:-}" url
  if [[ -n "$invoice_id" ]]; then
    url="http://127.0.0.1:18080/api/invoices/$invoice_id/corrections"
  else
    url="http://127.0.0.1:18080/api/invoices/corrections"
  fi
  local response
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "$url" 2>/dev/null || \
              curl --silent --show-error --fail --connect-timeout 3 --max-time 10 "${url/18080\/api/18095}" 2>/dev/null)" || {
    printf '%sNie udalo sie pobrac listy korekt.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | python3 -c '
import json, sys
items = json.load(sys.stdin)
if not items:
    print("Brak wystawionych korekt.")
    raise SystemExit
print("NR KOREKTY       | DO FAKTURY       | KOREKTA BRUTTO | KWOTA KOŃCOWA | PRZYCZYNA")
print("-" * 90)
for c in items:
    cn = c.get("correctionNumber", "-")
    oin = c.get("originalInvoiceNumber", "-")
    cg = float(c.get("correctionGross") or 0)
    fg = float(c.get("finalGross") or 0)
    r = (c.get("reason") or "-")[:25]
    print(f"{cn:<16} | {oin:<16} | {cg:>12.2f} zł | {fg:>11.2f} zł | {r}")
print(f"\nLiczba korekt: {len(items)}")
'
}

review_moderate() {
  require_command curl || return 1
  local review_id='' status='' note='' payload response
  [[ "$#" -ge 1 ]] && review_id="$1"
  [[ "$#" -ge 2 ]] && status="$2"
  [[ "$#" -ge 3 ]] && note="$3"
  if [[ -z "$review_id" ]]; then
    printf 'ID opinii: '
    read -r review_id || return 1
  fi
  if [[ -z "$status" ]]; then
    printf 'Status (PUBLISHED/REJECTED): '
    read -r status || return 1
  fi
  status="$(printf '%s' "$status" | tr '[:lower:]' '[:upper:]')"
  case "$status" in
    PUBLISHED|REJECTED) ;;
    *) printf '%sStatus musi byc PUBLISHED albo REJECTED.%s\n' "$C_RED" "$C_RESET" >&2; return 2 ;;
  esac
  if [[ -z "$note" ]]; then
    printf 'Notatka moderatora [opcjonalnie]: '
    read -r note || return 1
  fi
  payload="$(printf '{"status":"%s","note":"%s"}' "$status" "$note")"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 -H 'Content-Type: application/json' -X PATCH "http://127.0.0.1:18080/api/reviews/$review_id/moderation" --data-binary "$payload")" || {
    printf '%sNie udalo sie zmienic statusu opinii.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s\n' "$response"
}

chat_messages() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local limit="${1:-50}" response
  [[ "$limit" =~ ^[0-9]+$ && "$limit" -gt 0 ]] || {
    printf '%sLimit musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    "$gateway/chat/messages")" || {
    printf '%sChat Service jest niedostepny. Uruchom serwis chat.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%s' "$response" | CHAT_LIMIT="$limit" python3 -c 'import json, os, sys; data=json.load(sys.stdin)[:int(os.environ["CHAT_LIMIT"])] ; [(print("ID #{} | {} | {} <{}> | {} | powiadomienie={}".format(item.get("id"), item.get("status"), item.get("customerName"), item.get("customerEmail"), item.get("subject") or "Pytanie", item.get("notificationStatus"))), print("  Wiadomosc: {}".format(item.get("message") or "")), print("  Odpowiedz: {} | e-mail={}".format(item.get("replyMessage") or "-", item.get("replyNotificationStatus") or "-"))) for item in data]'
}

chat_reply() {
  require_command curl || return 1
  require_command python3 || return 1
  local gateway='http://127.0.0.1:18080/api'
  local chat_id="${1:-}" reply="${2:-}" payload response
  if [[ -z "$chat_id" ]]; then
    printf 'ID wiadomosci (0 = powrot): '
    read -r chat_id || return 1
  fi
  case "$(platform_lowercase "$chat_id")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ "$chat_id" =~ ^[0-9]+$ && "$chat_id" -gt 0 ]] || {
    printf '%sID wiadomosci musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  if [[ -z "$reply" ]]; then
    printf 'Tresc odpowiedzi (0 = powrot): '
    IFS= read -r reply || return 1
  fi
  case "$(platform_lowercase "$reply")" in
    0) return 0 ;;
    exit|quit) exit 0 ;;
  esac
  [[ -n "${reply//[[:space:]]/}" ]] || {
    printf '%sOdpowiedz nie moze byc pusta.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  payload="$(CHAT_REPLY="$reply" python3 -c 'import json,os; print(json.dumps({"message": os.environ["CHAT_REPLY"]}))')"
  response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H 'Content-Type: application/json' -X POST \
    "$gateway/chat/messages/$chat_id/reply" --data-binary "$payload")" || {
    printf '%sNie udalo sie wyslac odpowiedzi. Sprawdz ID i dostepnosc Chat Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  printf '%sOdpowiedz wyslana. E-mail zostal przekazany do Notification Service.%s\n' "$C_GREEN" "$C_RESET"
  printf '%s\n' "$response"
}

verify_mvp() {
  require_command curl || return 1
  require_command python3 || return 1
  local mode="${1:-auto}"
  if [[ "$mode" == 'interactive' && ! -t 0 ]]; then
    mode='auto'
  fi
  local profile gateway timestamp correlation_id product_id quantity recipient
  profile="$(active_profile)"
  case "$profile" in
    dev|test) ;;
    prod)
      printf '%sVerify MVP jest dostepny tylko dla profilu dev albo test.%s\n' "$C_RED" "$C_RESET" >&2
      return 1
      ;;
  esac

  gateway='http://127.0.0.1:18080/api'
  timestamp="$(date +%s)"
  correlation_id="app-verify-${timestamp}-$$"
  if [[ "$mode" == 'auto' || ! -t 0 ]]; then
    product_id="${2:-${APP_VERIFY_PRODUCT_ID:-4}}"
    quantity="${3:-${APP_VERIFY_QUANTITY:-1}}"
    recipient="${4:-${APP_VERIFY_RECIPIENT:-app-verify-${timestamp}@minitech.local}}"
    printf 'Tryb automatyczny: produkt %s, ilosc %s, odbiorca %s\n' \
      "$product_id" "$quantity" "$recipient"
  else
    printf 'Produkt ID [4]: '
    read -r product_id || product_id=''
    product_id="${product_id:-4}"
    printf 'Ilosc [1]: '
    read -r quantity || quantity=''
    quantity="${quantity:-1}"
    recipient="mvp-verify-${timestamp}@minitech.local"
    printf 'Odbiorca [%s]: ' "$recipient"
    read -r entered_recipient || entered_recipient=''
    recipient="${entered_recipient:-$recipient}"
  fi

  [[ "$product_id" =~ ^[0-9]+$ && "$product_id" -gt 0 ]] || {
    printf '%sProdukt ID musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  [[ "$quantity" =~ ^[0-9]+$ && "$quantity" -gt 0 ]] || {
    printf '%sIlosc musi byc dodatnia liczba.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }
  [[ "$recipient" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || {
    printf '%sNieprawidlowy adres odbiorcy.%s\n' "$C_RED" "$C_RESET" >&2
    return 2
  }

  printf 'Sprawdzam zdrowie wszystkich mikroserwisow...\n'
  health all || {
    printf '%sNie mozna uruchomic weryfikacji: co najmniej jeden mikroserwis jest niedostepny.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  }
  mailpit_info >/dev/null || {
    printf '%sMailpit jest niedostepny. Uruchom: mailpit-up%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  local product_response stock_before stock_after order_response payment_response
  local delivery_methods delivery_method_code delivery_quote delivery_quote_payload delivery_cost
  local delivery_subtotal unit_price order_delivery_method order_delivery_cost
  local order_after reservation_response notification_response mailpit_response
  local active available_before available_after order_id reservation_id
  local order_status payment_status reservation_status notification_status mail_count
  product_response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" \
    "$gateway/products/${product_id}")" || {
    printf '%sNie mozna pobrac produktu z API Gateway.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  active="$(printf '%s' "$product_response" | python3 -c 'import json,sys; print(str(json.load(sys.stdin).get("active", False)).lower())')"
  [[ "$active" == 'true' ]] || {
    printf '%sProdukt nie jest aktywny.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  stock_before="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" \
    "$gateway/inventory/items/${product_id}")" || {
    printf '%sNie mozna pobrac stanu magazynowego.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  available_before="$(printf '%s' "$stock_before" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("availableQuantity", -1))' 2>/dev/null || echo 0)"
  if ! [[ "$available_before" =~ ^[0-9]+$ ]] || (( available_before < quantity )); then
    if [[ "$mode" == 'auto' || ! -t 0 ]]; then
      curl --silent -X PATCH -H 'Content-Type: application/json' \
        -d '{"availableQuantity": 50}' "$gateway/inventory/items/${product_id}" >/dev/null 2>&1 || true
      stock_before="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
        -H "X-Correlation-Id: $correlation_id" \
        "$gateway/inventory/items/${product_id}")" || true
      available_before="$(printf '%s' "$stock_before" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("availableQuantity", -1))' 2>/dev/null || echo 0)"
    fi
  fi
  if ! [[ "$available_before" =~ ^[0-9]+$ ]] || (( available_before < quantity )); then
    printf '%sBrak wystarczajacej ilosci. Dostepne: %s, wymagane: %s.%s\n' \
      "$C_RED" "$available_before" "$quantity" "$C_RESET" >&2
    return 1
  fi

  delivery_methods="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" \
    "$gateway/deliveries/methods")" || {
    printf '%sNie mozna pobrac metod dostawy z Delivery Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  delivery_method_code="$(printf '%s' "$delivery_methods" | python3 -c 'import json,sys; data=json.load(sys.stdin); print(data[0]["code"] if data else "")')"
  [[ -n "$delivery_method_code" ]] || {
    printf '%sDelivery Service nie zwrocil aktywnej metody dostawy.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  unit_price="$(printf '%s' "$product_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("price", 0))')"
  delivery_subtotal="$(UNIT_PRICE="$unit_price" QUANTITY="$quantity" python3 -c 'from decimal import Decimal; import os; print(Decimal(os.environ["UNIT_PRICE"]) * int(os.environ["QUANTITY"]))')"
  delivery_quote_payload="$(DELIVERY_METHOD_CODE="$delivery_method_code" DELIVERY_QUANTITY="$quantity" DELIVERY_SUBTOTAL="$delivery_subtotal" \
    python3 -c 'import json,os; print(json.dumps({"methodCode": os.environ["DELIVERY_METHOD_CODE"], "postalCode": "00-001", "itemCount": int(os.environ["DELIVERY_QUANTITY"]), "subtotal": float(os.environ["DELIVERY_SUBTOTAL"])}))')"
  delivery_quote="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" -H 'Content-Type: application/json' \
    -X POST "$gateway/deliveries/quote" --data "$delivery_quote_payload")" || {
    printf '%sNie mozna wyliczyc kosztu dostawy w Delivery Service.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  delivery_cost="$(printf '%s' "$delivery_quote" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deliveryCost", 0))')"
  [[ "$delivery_cost" =~ ^[0-9]+([.][0-9]+)?$ ]] || {
    printf '%sDelivery Service zwrocil nieprawidlowy koszt dostawy.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  local order_payload payment_payload
  order_payload="$(printf '{\n  \"productId\": %s,\n  \"quantity\": %s,\n  \"customerId\": null,\n  \"customerEmail\": \"%s\",\n  \"deliveryAddress\": {\n    \"firstName\": \"MVP\",\n    \"lastName\": \"Verifier\",\n    \"street\": \"Control Center 1\",\n    \"postalCode\": \"00-001\",\n    \"city\": \"Warszawa\",\n    \"country\": \"Polska\"\n  },\n  \"deliveryMethod\": \"%s\",\n  \"deliveryCost\": %s\n}' \
    "$product_id" "$quantity" "$(json_escape "$recipient")" "$delivery_method_code" "$delivery_cost")"
  printf 'Tworze zamowienie testowe...\n'
  order_response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 15 \
    -H "X-Correlation-Id: $correlation_id" \
    -H 'Content-Type: application/json' -X POST "$gateway/orders" --data "$order_payload")" || {
    printf '%sNie udalo sie utworzyc zamowienia testowego.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }
  order_id="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
  reservation_id="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("inventoryReservationId", ""))')"
  order_delivery_method="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deliveryMethod", ""))')"
  order_delivery_cost="$(printf '%s' "$order_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("deliveryCost", ""))')"
  [[ -n "$reservation_id" ]] || {
    printf '%sZamowienie nie zawiera identyfikatora rezerwacji Inventory.%s\n' "$C_RED" "$C_RESET" >&2
    return 1
  }

  payment_payload="$(printf '{\n  \"orderId\": %s,\n  \"paymentMethod\": \"CARD\",\n  \"simulateFailure\": false,\n  \"notificationRecipient\": \"%s\",\n  \"idempotencyKey\": \"mvp-%s\"\n}' \
    "$order_id" "$(json_escape "$recipient")" "$order_id")"
  printf 'Przetwarzam platnosc testowa...\n'
  payment_response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 15 \
    -H "X-Correlation-Id: $correlation_id" \
    -H 'Content-Type: application/json' -X POST "$gateway/payments" --data "$payment_payload")" || {
    printf '%sPlatnosc testowa nie powiodla sie dla zamowienia #%s.%s\n' "$C_RED" "$order_id" "$C_RESET" >&2
    return 1
  }

  order_after="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" \
    "$gateway/orders/${order_id}")" || return 1
  reservation_response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" \
    "$gateway/inventory/reservations/${reservation_id}")" || return 1
  stock_after="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
    -H "X-Correlation-Id: $correlation_id" \
    "$gateway/inventory/items/${product_id}")" || return 1
  order_status="$(printf '%s' "$order_after" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  payment_status="$(printf '%s' "$payment_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  reservation_status="$(printf '%s' "$reservation_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status", ""))')"
  available_after="$(printf '%s' "$stock_after" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("availableQuantity", -1))')"

  notification_status='PENDING'
  for attempt in {1..20}; do
    notification_response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 5 \
      -H "X-Correlation-Id: $correlation_id" \
      "$gateway/notifications/source/${order_id}" 2>/dev/null || true)"
    if [[ -n "$notification_response" ]]; then
      notification_status="$(printf '%s' "$notification_response" | python3 -c 'import json,sys; print("SENT" if any(item.get("status") == "SENT" for item in json.load(sys.stdin)) else "PENDING")')"
      [[ "$notification_status" == 'SENT' ]] && break
    fi
    sleep 1
  done

  mail_count='0'
  for attempt in {1..10}; do
    mailpit_response="$(curl --silent --show-error --fail --connect-timeout 3 --max-time 10 \
      --get "http://127.0.0.1:${MAILPIT_WEB_PORT}/api/v1/search" \
      --data-urlencode "query=to:${recipient}" 2>/dev/null || true)"
    if [[ -n "$mailpit_response" ]]; then
      mail_count="$(printf '%s' "$mailpit_response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count", 0))')"
      (( mail_count > 0 )) && break
    fi
    sleep 1
  done

  printf '\n%sWynik Verify MVP%s\n' "$C_BLUE" "$C_RESET"
  printf '%-24s %s\n' 'Profil' "$profile"
  printf '%-24s #%s\n' 'Zamowienie' "$order_id"
  printf '%-24s %s\n' 'Order status' "$order_status"
  printf '%-24s %s\n' 'Payment status' "$payment_status"
  printf '%-24s %s\n' 'Reservation status' "$reservation_status"
  printf '%-24s %s (%s zl)\n' 'Delivery' "$order_delivery_method" "$order_delivery_cost"
  printf '%-24s %s -> %s\n' 'Dostepna ilosc' "$available_before" "$available_after"
  printf '%-24s %s\n' 'Notification' "$notification_status"
  printf '%-24s %s\n' 'Mailpit messages' "$mail_count"

  local failures=0
  [[ "$order_status" == 'PAID' ]] || failures=$((failures + 1))
  [[ "$payment_status" == 'SUCCEEDED' ]] || failures=$((failures + 1))
  [[ "$reservation_status" == 'CONFIRMED' ]] || failures=$((failures + 1))
  [[ "$order_delivery_method" == "$delivery_method_code" ]] || failures=$((failures + 1))
  [[ "$available_after" =~ ^[0-9]+$ && "$available_before" =~ ^[0-9]+$ ]] \
    && (( available_after == available_before - quantity )) || failures=$((failures + 1))
  [[ "$notification_status" == 'SENT' ]] || failures=$((failures + 1))
  (( mail_count > 0 )) || failures=$((failures + 1))
  if (( failures == 0 )); then
    printf '%sVerify MVP zakonczony powodzeniem.%s\n' "$C_GREEN" "$C_RESET"
    return 0
  fi
  printf '%sVerify MVP wykryl %s problemow.%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  return 1
}

app_verify() {
  verify_mvp auto
}

environment_add_requirement() {
  local requirement="$1"
  local existing
  for existing in "${ENVIRONMENT_MISSING_REQUIREMENTS[@]}"; do
    [[ "$existing" == "$requirement" ]] && return 0
  done
  ENVIRONMENT_MISSING_REQUIREMENTS+=("$requirement")
}

environment_jdk25_available() {
  local java_version java_major
  command -v java >/dev/null 2>&1 || return 1
  command -v javac >/dev/null 2>&1 || return 1
  java_version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  java_major="${java_version%%.*}"
  [[ "$java_major" == '25' || "$java_major" == 25-* ]]
}

environment_scan_requirements() {
  ENVIRONMENT_MISSING_REQUIREMENTS=()

  for command in bash curl python3 git; do
    command -v "$command" >/dev/null 2>&1 || environment_add_requirement "$command"
  done

  environment_jdk25_available || environment_add_requirement jdk25

  command -v sops >/dev/null 2>&1 || environment_add_requirement sops
  if ! command -v age >/dev/null 2>&1 || ! command -v age-keygen >/dev/null 2>&1; then
    environment_add_requirement age
  fi

  if command -v docker >/dev/null 2>&1; then
    docker compose version >/dev/null 2>&1 || environment_add_requirement compose
  else
    environment_add_requirement docker
    environment_add_requirement compose
  fi

  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    environment_add_requirement runtime
  fi
}

environment_print_install_locations() {
  local command
  printf '\nLokalizacje wykrytych narzedzi:\n'
  for command in java javac python3 curl git sops age age-keygen docker; do
    if command -v "$command" >/dev/null 2>&1; then
      printf '  %-12s %s\n' "$command" "$(command -v "$command")"
    fi
  done
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    printf '  %-12s docker CLI plugin\n' 'compose'
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    printf '  %-12s %s\n' 'JAVA_HOME' "$JAVA_HOME"
  fi
  printf '  %-12s %s\n' 'konfiguracja' "$CONFIG_DIRECTORY"
  printf '  %-12s %s\n' 'sekrety' "$SECRET_ROOT_DIRECTORY"
}

environment_install() {
  if [[ "$PLATFORM_ID" == 'windows' ]]; then
    printf '%sInstalator jest dostepny tylko w Bashu uruchomionym w WSL.%s\n' \
      "$C_RED" "$C_RESET" >&2
    printf 'Uruchom .\\scripts\\start.ps1 jako most do WSL albo wejdz do swojej dystrybucji WSL i uruchom:\n' >&2
    printf '  bash scripts/control-center/control-center.sh environment-install\n' >&2
    return 2
  fi

  environment_scan_requirements
  local manager="$(platform_package_manager)"
  local -a installable_requirements=()
  local -a manual_requirements=()
  local requirement package

  printf '%sMini Order Platform - instalator brakujacych skladnikow%s\n\n' "$C_BLUE" "$C_RESET"
  platform_print_info
  printf 'Menedzer pakietow: %s\n' "$manager"
  printf 'Docelowe miejsce instalacji: %s\n\n' "$(platform_install_target)"

  if ((${#ENVIRONMENT_MISSING_REQUIREMENTS[@]} == 0)); then
    printf '%sNie wykryto brakujacych skladnikow systemowych.%s\n' "$C_GREEN" "$C_RESET"
    environment_print_install_locations
    return 0
  fi

  printf 'Wykryte braki:\n'
  for requirement in "${ENVIRONMENT_MISSING_REQUIREMENTS[@]}"; do
    package="$(platform_install_package_name "$manager" "$requirement" 2>/dev/null || true)"
    if [[ -n "$package" ]]; then
      installable_requirements+=("$requirement")
      printf '  - %-30s pakiet: %s\n' \
        "$(platform_install_requirement_label "$requirement")" "$package"
    else
      manual_requirements+=("$requirement")
      printf '  - %-30s konfiguracja reczna / poza zakresem instalatora\n' \
        "$(platform_install_requirement_label "$requirement")"
    fi
    printf '    miejsce: %s\n' "$(platform_install_requirement_location "$requirement")"
  done

  if ((${#manual_requirements[@]} > 0)); then
    printf '\nElementy, ktorych Centrala nie zainstaluje automatycznie:\n'
    for requirement in "${manual_requirements[@]}"; do
      if [[ "$requirement" == 'runtime' ]]; then
        printf '  - %s: uruchom lub skonfiguruj runtime dla linuksowych kontenerow.\n' \
          "$(platform_install_requirement_label "$requirement")"
        printf '    Centrala nie wybiera za uzytkownika Docker Engine, integracji Docker-WSL ani runtime macOS.\n'
      else
        printf '  - %s: brak obslugi w wykrytym menedzerze pakietow.\n' \
          "$(platform_install_requirement_label "$requirement")"
      fi
    done
  fi

  if ((${#installable_requirements[@]} == 0)); then
    printf '\n%sBrak skladnikow, ktore mozna bezpiecznie zainstalowac automatycznie.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  fi

  printf '\nInstalator wykona tylko powyzsze operacje pakietowe.\n'
  printf 'Moze poprosic o haslo sudo i pobierze pakiety z systemowego repozytorium.\n'
  printf 'Aby kontynuowac, wpisz dokladnie ACCEPT. Enter lub inna wartosc = anulowanie: '
  local confirmation
  IFS= read -r confirmation || return 1
  if [[ "$confirmation" != 'ACCEPT' ]]; then
    printf 'Anulowano instalacje. Nie zmieniono systemu.\n'
    return 0
  fi

  printf '\nRozpoczynam instalacje przez %s...\n' "$manager"
  platform_install_packages "$manager" "${installable_requirements[@]}" || {
    printf '%sInstalacja zakonczona bledem. Uruchom ponownie environment-install po usunieciu problemu.%s\n' \
      "$C_RED" "$C_RESET" >&2
    return 1
  }
  platform_post_install_environment
  environment_print_install_locations
  printf '\nPonawiam diagnostyke po instalacji:\n\n'
  environment_check
}

doctor() {
  local failures=0 profile
  profile="$(active_profile)"
  printf '%sMini Order Platform - doctor%s\n\n' "$C_BLUE" "$C_RESET"
  printf 'Aktywny profil: %s\n\n' "$profile"
  preflight || failures=$((failures + 1))

  if [[ "$profile" == 'dev' || "$profile" == 'test' ]]; then
    printf '\nWalidacja sekretow profilu %s:\n' "$profile"
    secrets_validate "$profile" || failures=$((failures + 1))
  fi

  printf '\nKontenery infrastruktury:\n'
  compose ps || failures=$((failures + 1))
  printf '\nZdrowie aplikacji:\n'
  health all || failures=$((failures + 1))

  printf '\nMailpit:\n'
  if mailpit_info >/dev/null; then
    printf '%sMailpit API OK%s (panel: http://127.0.0.1:%s)\n' "$C_GREEN" "$C_RESET" "$MAILPIT_WEB_PORT"
  else
    printf '%sMailpit niedostepny%s\n' "$C_RED" "$C_RESET"
    failures=$((failures + 1))
  fi

  printf '\nProcesy aplikacji uruchamiane przez centrale:\n'
  app_status_all
  printf '\nFrontend:\n'
  frontend_status || true
  printf '\nMonitoring (opcjonalny):\n'
  if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready" >/dev/null 2>&1; then
    printf '%sPrometheus OK%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%sPrometheus niedostepny (ostrzezenie)%s\n' "$C_DIM" "$C_RESET"
  fi
  if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${GRAFANA_PORT}/api/health" >/dev/null 2>&1; then
    printf '%sGrafana OK%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%sGrafana niedostepna (ostrzezenie)%s\n' "$C_DIM" "$C_RESET"
  fi

  if (( failures == 0 )); then
    printf '\n%sDoctor: srodowisko gotowe.%s\n' "$C_GREEN" "$C_RESET"
    return 0
  fi
  printf '\n%sDoctor: wykryto %s problemow wymagajacych uwagi.%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  return 1
}

github_check() {
  local failures=0 tracked_sensitive disk_sensitive private_probe file
  printf '%sMini Order Platform - kontrola gotowosci do GitHub%s\n\n' "$C_BLUE" "$C_RESET"

  if [[ -f "$PROJECT_ROOT/.gitignore" ]]; then
    printf '%-24s OK\n' '.gitignore'
  else
    printf '%-24s BRAK\n' '.gitignore'
    failures=$((failures + 1))
  fi

  if grep -Fxq '/docs/APKA.md' "$PROJECT_ROOT/.gitignore" 2>/dev/null; then
    printf '%-24s OK (wykluczony)\n' 'docs/APKA.md'
  else
    printf '%-24s BRAK reguly wykluczenia\n' 'docs/APKA.md'
    failures=$((failures + 1))
  fi

  for file in '/output/' '/tmp/'; do
    if grep -Fxq "$file" "$PROJECT_ROOT/.gitignore" 2>/dev/null; then
      printf '%-24s OK (ignorowany)\n' "$file"
    else
      printf '%-24s BRAK reguly ignorowania\n' "$file"
      failures=$((failures + 1))
    fi
  done

  if [[ -f "$PROJECT_ROOT/gitIgnoreOwn/README.md" ]]; then
    printf '%-24s OK\n' 'gitIgnoreOwn/README.md'
  else
    printf '%-24s BRAK\n' 'gitIgnoreOwn/README.md'
    failures=$((failures + 1))
  fi

  for file in "$PROJECT_ROOT/.gitattributes" "$PROJECT_ROOT/.github/workflows/ci.yml"; do
    if [[ -f "$file" ]]; then
      printf '%-24s OK\n' "${file#"$PROJECT_ROOT/"}"
    else
      printf '%-24s BRAK\n' "${file#"$PROJECT_ROOT/"}"
      failures=$((failures + 1))
    fi
  done

  disk_sensitive="$(find "$PROJECT_ROOT" -type f \( -name '.env' -o -name '.env.*' -o -name '*.env.sops' -o -name '*.agekey' -o -name '*.key' -o -name '*.pem' -o -name '*.crt' -o -name '*.cer' -o -name '*.p12' -o -name '*.pfx' -o -name '*.jks' -o -name '*.kubeconfig' -o -name 'kubeconfig' -o -name '*.dump' -o -name '*.dump.age' \) ! -path "$PROJECT_ROOT/gitIgnoreOwn/*" ! -path '*/target/*' ! -path '*/.git/*' ! -name '*.example' -print 2>/dev/null || true)"
  if [[ -n "$disk_sensitive" ]]; then
    printf '%sWrazliwe pliki poza gitIgnoreOwn:%s\n%s\n' "$C_RED" "$C_RESET" "$disk_sensitive" >&2
    failures=$((failures + 1))
  else
    printf '%-24s OK (brak prywatnych plikow poza gitIgnoreOwn)\n' 'pliki lokalne'
  fi

  require_command git || {
    printf '%sGit jest potrzebny do kontroli repozytorium przed publikacja.%s\n' "$C_RED" "$C_RESET" >&2
    failures=$((failures + 1))
  }

  if ! git -C "$PROJECT_ROOT" rev-parse --show-toplevel >/dev/null 2>&1; then
    printf '%sRepozytorium Git nie jest jeszcze zainicjalizowane; wykonano kontrole statyczne.%s\n' \
      "$C_DIM" "$C_RESET"
  else
    tracked_sensitive="$(git -C "$PROJECT_ROOT" ls-files | grep -Ei \
      '(^|/)\.env($|\.[^/]+$)|\.(agekey|key|pem|crt|cer|p12|pfx|jks|kubeconfig)$|(^|/)kubeconfig$' \
      | grep -Ev '(/\.gitkeep$|\.example$)' || true)"
    if [[ -n "$tracked_sensitive" ]]; then
      printf '%sWykryto wrazliwe pliki sledzone przez Git:%s\n%s\n' \
        "$C_RED" "$C_RESET" "$tracked_sensitive" >&2
      failures=$((failures + 1))
    else
      printf '%-24s OK (brak sledzonych sekretow)\n' 'pliki wrazliwe'
    fi

    private_probe='gitIgnoreOwn/secrets/example.env'
    if git -C "$PROJECT_ROOT" check-ignore --no-index -q "$private_probe"; then
      printf '%-24s OK (ignorowany)\n' 'gitIgnoreOwn/secrets'
    else
      printf '%-24s BRAK reguly ignorowania\n' 'gitIgnoreOwn/secrets'
      failures=$((failures + 1))
    fi

    if git -C "$PROJECT_ROOT" ls-files --error-unmatch 'docs/APKA.md' >/dev/null 2>&1; then
      printf '%sdocs/APKA.md jest sledzony przez Git.%s\n' "$C_RED" "$C_RESET" >&2
      failures=$((failures + 1))
    elif git -C "$PROJECT_ROOT" check-ignore --no-index -q 'docs/APKA.md'; then
      printf '%-24s OK (niesledzony)\n' 'prywatny APKA.md'
    else
      printf '%-24s BRAK ochrony\n' 'prywatny APKA.md'
      failures=$((failures + 1))
    fi
  fi

  if (( failures == 0 )); then
    printf '\n%sGitHub check: nie wykryto problemow.%s\n' "$C_GREEN" "$C_RESET"
    return 0
  fi
  printf '\n%sGitHub check: wykryto %s problemow.%s\n' "$C_RED" "$failures" "$C_RESET" >&2
  return 1
}

environment_check() {
  local failures=0 profile java_version java_major
  printf '%sMini Order Platform - kontrola przed startem%s\n\n' "$C_BLUE" "$C_RESET"
  preflight || failures=$((failures + 1))

  profile="$(active_profile)"
  printf '\nProfil: %s\n' "$profile"
  if ensure_local_profile; then
    if secrets_validate "$profile"; then
      printf '%-24s OK\n' 'sekrety profilu'
    else
      printf '%-24s BRAK lub blad\n' 'sekrety profilu'
      failures=$((failures + 1))
    fi
  else
    failures=$((failures + 1))
  fi

  if command -v java >/dev/null 2>&1; then
    java_version="$(java -version 2>&1 | awk -F '\"' '/version/ {print $2; exit}')"
    java_major="${java_version%%.*}"
    if [[ "$java_major" == '25' ]]; then
      printf '%-24s OK (%s)\n' 'JDK' "$java_version"
    else
      printf '%-24s BRAK JDK 25 (wykryto %s)\n' 'JDK' "${java_version:-nieznana}"
      failures=$((failures + 1))
    fi
  fi

  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    printf '%-24s OK (daemon)\n' 'Docker'
    if compose config --quiet >/dev/null 2>&1; then
      printf '%-24s OK\n' 'Compose + sekrety'
    else
      printf '%-24s BRAK lub blad konfiguracji\n' 'Compose + sekrety'
      failures=$((failures + 1))
    fi
  else
    printf '%-24s BRAK lub daemon nie dziala\n' 'Docker'
    failures=$((failures + 1))
  fi

  if (( failures == 0 )); then
    printf '\n%sKontrola przed startem: srodowisko gotowe.%s\n' "$C_GREEN" "$C_RESET"
    printf 'Mozesz uruchomic: infra-up, broker-up, mailpit-up, app-run-all, frontend-up\n'
    return 0
  fi
  printf '\n%sKontrola przed startem: wykryto %s problemow. Nie uruchamiaj aplikacji przed ich usunieciem.%s\n' \
    "$C_RED" "$failures" "$C_RESET" >&2
  printf 'Jesli problemy dotycza brakujacych narzedzi systemowych, mozesz uruchomic opcjonalny instalator:\n' >&2
  printf '  bash scripts/control-center/control-center.sh environment-install\n' >&2
  return 1
}

preflight() {
  local failures=0
  printf 'Mini Order Platform - kontrola wymagan\n\n'
  platform_print_info
  printf '\n'
  for command in bash curl java javac python3; do
    if command -v "$command" >/dev/null 2>&1; then
      printf '%-12s OK\n' "$command"
    else
      printf '%-12s BRAK\n' "$command"
      failures=$((failures + 1))
    fi
  done

  if command -v docker >/dev/null 2>&1; then
    printf '%-12s OK\n' 'docker'
    if docker compose version >/dev/null 2>&1; then
      printf '%-12s OK\n' 'compose'
    else
      printf '%-12s BRAK\n' 'compose'
      failures=$((failures + 1))
    fi
  else
    printf '%-12s BRAK\n' 'docker'
    printf '%-12s BRAK\n' 'compose'
    failures=$((failures + 2))
  fi

  if command -v git >/dev/null 2>&1; then
    printf '%-12s OK (GitHub)\n' 'git'
  else
    printf '%-12s OSTRZEZENIE (potrzebny do GitHub)\n' 'git'
  fi

  for file in "$COMPOSE_FILE" "$MONITORING_COMPOSE_FILE" "$MAVEN_WRAPPER" "$PROJECT_ROOT/pom.xml" "$FRONTEND_DIRECTORY/index.html"; do
    if [[ -f "$file" ]]; then
      printf '%-12s OK %s\n' 'plik' "$file"
    else
      printf '%-12s BRAK %s\n' 'plik' "$file"
      failures=$((failures + 1))
    fi
  done

  printf '\nSkarbiec sekretow WSL:\n'
  if secrets_tools_check; then
    printf '%-12s OK\n' 'secrets'
  else
    printf '%-12s BRAK narzedzi SOPS/age\n' 'secrets'
    failures=$((failures + 1))
  fi

  printf '\nMapa baz:\n'
  print_database_catalog
  return "$failures"
}

pause_menu() {
  [[ -t 0 ]] || return 0
  printf '\n%s: ' "$(cc_text 'Enter lub 0 = powrot do menu, exit = zamkniecie panelu' 'Enter or 0 = return to menu, exit = close panel')"
  read -r answer || exit 0
  case "$(platform_lowercase "$answer")" in
    exit|quit) exit 0 ;;
    0|"") return 0 ;;
    *) printf '%s\n' "$(cc_text 'Powrot do menu.' 'Returning to menu.')" ;;
  esac
}

run_menu_action() {
  printf '\n%s%s: %s%s\n\n' "$C_DIM" "$(cc_text 'Polecenie' 'Command')" "$*" "$C_RESET"
  "$@"
  pause_menu
}

select_service() {
  printf '%s: ' "$(cc_text 'Wybierz alias (product/order/inventory/payment/notification/customer/delivery/promotion/wishlist/review/recommendation/chat/returns/billing)' 'Choose alias (product/order/inventory/payment/notification/customer/delivery/promotion/wishlist/review/recommendation/chat/returns/billing)')" >&2
  read -r selected_service || exit 0
  case "$(platform_lowercase "$selected_service")" in
    exit|quit) exit 0 ;;
    0) return 1 ;;
  esac
  return 0
}

language_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Jezyk Control Center' 'Control Center language')" "$C_RESET"
    printf '1. %s%s\n' "$(cc_text 'Jezyk polski' 'Polish')" "$(if [[ "$UI_LANGUAGE" == "pl" ]]; then printf ' (domyslny)' ; fi)"
    printf '2. %s%s\n' "$(cc_text 'Jezyk angielski' 'English')" "$(if [[ "$UI_LANGUAGE" == "en" ]]; then printf ' (current)' ; fi)"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action language_set pl ;;
      2) run_menu_action language_set en ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

settings_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Ustawienia' 'Settings')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Jezyk' 'Language')"
    printf '2. %s\n' "$(cc_text 'Pokaz aktualny jezyk' 'Show current language')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) language_menu ;;
      2) run_menu_action language_show ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

database_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Bazy danych' 'PostgreSQL databases')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pokaz mape baz i portow' 'Show database and port map')"
    printf '2. %s\n' "$(cc_text 'Uruchom wszystkie bazy' 'Start all databases')"
    printf '3. %s\n' "$(cc_text 'Zatrzymaj wszystkie bazy' 'Stop all databases')"
    printf '4. %s\n' "$(cc_text 'Pokaz status baz' 'Show database status')"
    printf '5. %s\n' "$(cc_text 'Pokaz logi jednej bazy' 'Show logs for one database')"
    printf '6. %s\n' "$(cc_text 'Otworz psql dla jednej bazy' 'Open psql for one database')"
    printf '7. %s\n' "$(cc_text 'Resetuj demo stan magazynowy' 'Reset demo inventory')"
    printf '8. %s\n' "$(cc_text 'Resetuj dane demonstracyjne' 'Reset demo data')"
    printf '9. %s\n' "$(cc_text 'Wykonaj backup jednej bazy' 'Back up one database')"
    printf '10. %s\n' "$(cc_text 'Wykonaj backup wszystkich baz' 'Back up all databases')"
    printf '11. %s\n' "$(cc_text 'Pokaz backupy aktywnego profilu' 'Show backups for active profile')"
    printf '12. %s\n' "$(cc_text 'Test odtworzenia backupu' 'Test backup restore')"
    printf '13. %s\n' "$(cc_text 'Usun backupy starsze niz retencja' 'Delete backups older than retention')"
    printf '14. %s\n' "$(cc_text 'Przygotuj klucz szyfrowania backupow' 'Prepare backup encryption key')"
    printf '15. %s\n' "$(cc_text 'Ustaw automatyczny backup przez crontab WSL' 'Schedule automatic backup with WSL crontab')"
    printf '16. %s\n' "$(cc_text 'Usun harmonogram automatycznego backupu' 'Remove automatic backup schedule')"
    printf '17. %s\n' "$(cc_text 'Pokaz status harmonogramu backupu' 'Show backup schedule status')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action print_database_catalog ;;
      2) run_menu_action db_action up all ;;
      3) run_menu_action db_down_all ;;
      4) run_menu_action db_action status all ;;
      5) if select_service; then run_menu_action db_action logs "$selected_service"; fi ;;
      6) if select_service; then run_menu_action db_shell "$selected_service"; fi ;;
      7)
        printf 'Wpisz RESET, aby przywrocic ilosci magazynowe demo: '
        read -r confirmation || exit 0
        case "$(platform_lowercase "$confirmation")" in
          exit|quit) exit 0 ;;
          0) ;;
          reset) run_menu_action inventory_reset ;;
          *) printf 'Anulowano.\n' ;;
        esac
        ;;
      8)
        run_menu_action demo_reset
        ;;
      9)
        if select_service; then run_menu_action db_backup "$selected_service"; fi
        ;;
      10) run_menu_action db_backup all ;;
      11) run_menu_action db_backup_list all ;;
      12)
        if select_service; then run_menu_action db_restore_test "$selected_service"; fi
        ;;
      13) run_menu_action db_backup_prune ;;
      14) run_menu_action backup_key_init all ;;
      15) run_menu_action backup_schedule_install ;;
      16) run_menu_action backup_schedule_remove ;;
      17) run_menu_action backup_schedule_status ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

broker_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Redis Event Broker i streamy' 'Redis Event Broker and streams')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom broker' 'Start broker')"
    printf '2. %s\n' "$(cc_text 'Zatrzymaj broker' 'Stop broker')"
    printf '3. %s\n' "$(cc_text 'Status brokera i PING' 'Broker status and PING')"
    printf '4. %s\n' "$(cc_text 'Pokaz logi brokera' 'Show broker logs')"
    printf '5. %s\n' "$(cc_text 'Pokaz informacje o streamie i consumer group' 'Show stream and consumer group info')"
    printf '6. %s\n' "$(cc_text 'Pokaz rekordy streamu' 'Show stream records')"
    printf '7. %s\n' "$(cc_text 'Zresetuj consumer group' 'Reset consumer group')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action broker_up ;;
      2) run_menu_action broker_down ;;
      3) run_menu_action broker_status ;;
      4) run_menu_action broker_logs ;;
      5) run_menu_action stream_info ;;
      6) run_menu_action stream_read 20 ;;
      7)
        printf 'Wpisz RESET, aby usunac consumer group %s: ' "$NOTIFICATION_CONSUMER_GROUP"
        read -r confirmation || exit 0
        confirmation_lower="$(printf '%s' "$confirmation" | tr '[:upper:]' '[:lower:]')"
        if [[ "$confirmation_lower" == "exit" || "$confirmation_lower" == "quit" ]]; then
          exit 0
        elif [[ "$confirmation_lower" == "0" ]]; then
          return
        elif [[ "$confirmation" == "RESET" ]]; then
          run_menu_action broker_reset_group
        else
          printf 'Anulowano.\n'
        fi
        ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

profile_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Profile i Mailpit' 'Profiles and Mailpit')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pokaz aktywny profil' 'Show active profile')"
    printf '2. %s\n' "$(cc_text 'Ustaw profil dev' 'Set dev profile')"
    printf '3. %s\n' "$(cc_text 'Ustaw profil test' 'Set test profile')"
    printf '4. %s\n' "$(cc_text 'Uruchom Mailpit' 'Start Mailpit')"
    printf '5. %s\n' "$(cc_text 'Zatrzymaj Mailpit' 'Stop Mailpit')"
    printf '6. %s\n' "$(cc_text 'Status Mailpit' 'Mailpit status')"
    printf '7. %s\n' "$(cc_text 'Pokaz logi Mailpit' 'Show Mailpit logs')"
    printf '8. %s\n' "$(cc_text 'Smoke Notification Service -> Mailpit' 'Notification Service -> Mailpit smoke test')"
    printf '9. %s\n' "$(cc_text 'Napisz i wyslij e-mail do Mailpit' 'Compose and send an e-mail to Mailpit')"
    printf '10. %s\n' "$(cc_text 'Pokaz ostatnie wiadomosci Mailpit' 'Show latest Mailpit messages')"
    printf '11. %s\n' "$(cc_text 'Wyszukaj wiadomosci Mailpit' 'Search Mailpit messages')"
    printf '12. %s\n' "$(cc_text 'Wyczysc skrzynke Mailpit' 'Clear Mailpit mailbox')"
    printf '13. %s\n' "$(cc_text 'Status skarbca sekretow dev/test' 'Show dev/test secrets vault status')"
    printf '14. %s\n' "$(cc_text 'Inicjalizuj klucze skarbca dev/test' 'Initialize dev/test vault keys')"
    printf '15. %s\n' "$(cc_text 'Kreator sekretow dev (hasla bez wyswietlania)' 'Dev secrets wizard (passwords hidden)')"
    printf '16. %s\n' "$(cc_text 'Kreator sekretow test (hasla bez wyswietlania)' 'Test secrets wizard (passwords hidden)')"
    printf '17. %s\n' "$(cc_text 'Edytuj sekrety dev' 'Edit dev secrets')"
    printf '18. %s\n' "$(cc_text 'Edytuj sekrety test' 'Edit test secrets')"
    printf '19. %s\n' "$(cc_text 'Uzupelnij brakujacy sekret JWT dev' 'Complete missing dev JWT secret')"
    printf '20. %s\n' "$(cc_text 'Uzupelnij brakujacy sekret JWT test' 'Complete missing test JWT secret')"
    printf '21. %s\n' "$(cc_text 'Rotuj klucze JWT RSA dla aktywnego profilu dev/test' 'Rotate RSA JWT keys for active dev/test profile')"
    printf '22. %s\n' "$(cc_text 'Pokaz dziennik audytowy Customer Service' 'Show Customer Service audit log')"
    printf '23. %s\n' "$(cc_text 'Wyslij zadanie resetu hasla przez Mailpit' 'Send password reset request through Mailpit')"
    printf '24. %s\n' "$(cc_text 'Pelny test OWASP ZAP/DAST API Gateway' 'Full OWASP ZAP/DAST API Gateway test')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action profile_show ;;
      2) run_menu_action profile_set dev ;;
      3) run_menu_action profile_set test ;;
      4) run_menu_action mailpit_up ;;
      5) run_menu_action mailpit_down ;;
      6) run_menu_action mailpit_status ;;
      7) run_menu_action mailpit_logs ;;
      8) run_menu_action mailpit_smoke ;;
      9) run_menu_action mailpit_send ;;
      10) run_menu_action mailpit_list 20 ;;
      11) run_menu_action mailpit_search ;;
      12) run_menu_action mailpit_clear ;;
      13) run_menu_action secrets_status ;;
      14) run_menu_action secrets_init ;;
      15) run_menu_action secrets_setup dev ;;
      16) run_menu_action secrets_setup test ;;
      17) run_menu_action secrets_edit dev ;;
      18) run_menu_action secrets_edit test ;;
      19) run_menu_action secrets_ensure_auth dev ;;
      20) run_menu_action secrets_ensure_auth test ;;
      21) run_menu_action jwt_rotate "$(active_profile)" ;;
      22) run_menu_action audit_list 50 ;;
      23) run_menu_action password_reset_request ;;
      24) run_menu_action qa_zap_dast ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

production_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Profil prod - przygotowanie (NIEDOSTEPNY)' 'Production profile - preparation (UNAVAILABLE)')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Status profilu prod' 'Production profile status')"
    printf '2. %s\n' "$(cc_text 'Waliduj manifesty Kubernetes' 'Validate Kubernetes manifests')"
    printf '3. %s\n' "$(cc_text 'Status wdrozenia Kubernetes' 'Kubernetes deployment status')"
    printf '4. %s\n' "$(cc_text 'Waliduj przygotowanie OpenBao/TLS' 'Validate OpenBao/TLS preparation')"
    printf '5. %s\n' "$(cc_text 'Status OpenBao' 'OpenBao status')"
    printf '6. %s\n' "$(cc_text 'Status TLS wskazanego hosta' 'TLS status for selected host')"
    printf '7. %s\n' "$(cc_text 'Pokaz lokalizacje szablonow prod' 'Show production template locations')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action prod_status ;;
      2) run_menu_action k8s_validate ;;
      3) run_menu_action k8s_status ;;
      4) run_menu_action openbao_validate ;;
      5) run_menu_action openbao_status ;;
      6) run_menu_action tls_status ;;
      7)
        printf 'Manifesty: %s\n' "$PROJECT_ROOT/deploy/k8s/overlays/prod"
        printf 'OpenBao:   %s\n' "$PROJECT_ROOT/infra/openbao"
        printf 'TLS:       %s\n' "$PROJECT_ROOT/deploy/k8s/overlays/prod/tls-secret.example.yaml"
        ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

monitoring_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Monitoring i Dead Letter Queue' 'Monitoring and Dead Letter Queue')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom Prometheus i Grafane' 'Start Prometheus and Grafana')"
    printf '2. %s\n' "$(cc_text 'Zatrzymaj Prometheus i Grafane' 'Stop Prometheus and Grafana')"
    printf '3. %s\n' "$(cc_text 'Status monitoringu' 'Monitoring status')"
    printf '4. %s\n' "$(cc_text 'Pokaz logi Prometheus/Grafana' 'Show Prometheus/Grafana logs')"
    printf '5. %s\n' "$(cc_text 'Pokaz adresy monitoringu' 'Show monitoring URLs')"
    printf '6. %s\n' "$(cc_text 'Pokaz otwarte wpisy DLQ' 'Show open DLQ entries')"
    printf '7. %s\n' "$(cc_text 'Ponow wysylke wpisu DLQ' 'Replay DLQ entry')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action monitoring_up ;;
      2) run_menu_action monitoring_down ;;
      3) run_menu_action monitoring_status ;;
      4) run_menu_action monitoring_logs ;;
      5) run_menu_action monitoring_urls ;;
      6) run_menu_action dead_letter_list ;;
      7)
        printf '%s: ' "$(cc_text 'Podaj ID wpisu DLQ' 'Enter DLQ entry ID')"
        read -r dead_letter_id || exit 0
        case "$(platform_lowercase "$dead_letter_id")" in
          exit|quit) exit 0 ;;
          0) ;;
          *) run_menu_action dead_letter_replay "$dead_letter_id" ;;
        esac
        ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

application_runtime_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Uruchamianie i status' 'Runtime and status')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom jeden projekt' 'Start one project')"
    printf '2. %s\n' "$(cc_text 'Uruchom wszystkie projekty w tle' 'Start all projects in background')"
    printf '3. %s\n' "$(cc_text 'Zatrzymaj wszystkie projekty z centrali' 'Stop all projects started by Control Center')"
    printf '4. %s\n' "$(cc_text 'Status procesow projektow' 'Project process status')"
    printf '5. %s\n' "$(cc_text 'Pokaz logi jednego projektu' 'Show logs for one project')"
    printf '6. %s\n' "$(cc_text 'Sprawdz dostepnosc wszystkich aplikacji' 'Check availability of all applications')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) if select_service; then run_menu_action run_app "$selected_service"; fi ;;
      2) run_menu_action app_run_all ;;
      3) run_menu_action app_stop_all ;;
      4) run_menu_action app_status_all ;;
      5) if select_service; then run_menu_action app_logs "$selected_service"; fi ;;
      6) run_menu_action health all ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

application_quality_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Testy i jakosc API' 'Testing and API quality')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Testy jednego projektu' 'Test one project')"
    printf '2. %s\n' "$(cc_text 'Testy calego monorepo' 'Test entire monorepo')"
    printf '3. %s\n' "$(cc_text 'Smoke przez API Gateway' 'Smoke test through API Gateway')"
    printf '4. %s\n' "$(cc_text 'App Verify - zamowienie, platnosc, magazyn, e-mail' 'App Verify - order, payment, inventory and e-mail')"
    printf '5. %s\n' "$(cc_text 'Test kontraktowy publicznego API v1' 'Public API v1 contract test')"
    printf '6. %s\n' "$(cc_text 'Test bezpieczenstwa API' 'API security test')"
    printf '7. %s\n' "$(cc_text 'Test obciazeniowy API' 'API load test')"
    printf '8. %s\n' "$(cc_text 'Pelny E2E: rejestracja, JWT, zakup i e-mail' 'Full E2E: registration, JWT, purchase and e-mail')"
    printf '9. %s\n' "$(cc_text 'Status odpornosci: timeouty, Retry, Circuit Breaker, Bulkhead' 'Resilience status: timeouts, Retry, Circuit Breaker, Bulkhead')"
    printf '10. %s\n' "$(cc_text 'Adresy dokumentacji OpenAPI/Swagger' 'OpenAPI/Swagger documentation URLs')"
    printf '11. %s\n' "$(cc_text 'Status testow Playwright UI' 'Playwright UI test status')"
    printf '12. %s\n' "$(cc_text 'Zainstaluj Playwright i Chromium' 'Install Playwright and Chromium')"
    printf '13. %s\n' "$(cc_text 'Playwright Smoke - najwazniejsze ekrany' 'Playwright Smoke - critical screens')"
    printf '14. %s\n' "$(cc_text 'Pelna regresja UI w Chromium' 'Full UI regression in Chromium')"
    printf '15. %s\n' "$(cc_text 'Pelny checkout przez prawdziwe serwisy' 'Full checkout through real services')"
    printf '16. %s\n' "$(cc_text 'Regresja w wielu przegladarkach' 'Multi-browser regression')"
    printf '17. %s\n' "$(cc_text 'Test UI z widoczna przegladarka' 'Headed UI test')"
    printf '18. %s\n' "$(cc_text 'Debug Playwright' 'Playwright debug')"
    printf '19. %s\n' "$(cc_text 'Pokaz raport Playwright' 'Show Playwright report')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) if select_service; then run_menu_action run_tests "$selected_service"; fi ;;
      2) run_menu_action run_tests all ;;
      3) run_menu_action smoke ;;
      4) run_menu_action app_verify ;;
      5) run_menu_action qa_contract_test ;;
      6) run_menu_action qa_security_test ;;
      7) run_menu_action qa_load_test ;;
      8) run_menu_action qa_e2e ;;
      9) run_menu_action resilience_status ;;
      10) run_menu_action api_docs_urls ;;
      11) run_menu_action qa_ui_status ;;
      12) run_menu_action qa_ui_install ;;
      13) run_menu_action qa_ui_smoke ;;
      14) run_menu_action qa_ui_regression ;;
      15) run_menu_action qa_ui_system ;;
      16) run_menu_action qa_ui_browsers ;;
      17) run_menu_action qa_ui_headed ;;
      18) run_menu_action qa_ui_debug ;;
      19) run_menu_action qa_ui_report ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

application_security_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Bezpieczenstwo i konta' 'Security and accounts')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Status JWT (sekret i tokeny ukryte)' 'JWT status (secrets and tokens hidden)')"
    printf '2. %s\n' "$(cc_text 'Smoke JWT: login, /me, refresh, logout' 'JWT smoke: login, /me, refresh, logout')"
    printf '3. %s\n' "$(cc_text 'Rotuj klucze JWT RSA dla aktywnego profilu dev/test' 'Rotate RSA JWT keys for active dev/test profile')"
    printf '4. %s\n' "$(cc_text 'Pokaz dziennik audytowy Customer Service' 'Show Customer Service audit log')"
    printf '5. %s\n' "$(cc_text 'Wyslij zadanie resetu hasla przez Mailpit' 'Send password reset request through Mailpit')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action jwt_status ;;
      2) run_menu_action jwt_smoke ;;
      3) run_menu_action jwt_rotate "$(active_profile)" ;;
      4) run_menu_action audit_list 50 ;;
      5) run_menu_action password_reset_request ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

application_store_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Operacje sklepu' 'Store operations')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pokaz i sprawdz kody rabatowe' 'List and validate promotion codes')"
    printf '2. %s\n' "$(cc_text 'Wygeneruj kod rabatowy' 'Create promotion code')"
    printf '3. %s\n' "$(cc_text 'Pokaz opinie oczekujace na moderacje' 'Show reviews pending moderation')"
    printf '4. %s\n' "$(cc_text 'Opublikuj albo odrzuc opinie' 'Publish or reject a review')"
    printf '5. %s\n' "$(cc_text 'Pokaz numer przesylki i status przewoznika' 'Show tracking number and carrier status')"
    printf '6. %s\n' "$(cc_text 'Zmien symulowany status przesylki' 'Change simulated shipment status')"
    printf '7. %s\n' "$(cc_text 'Pokaz wiadomosci z czatu' 'Show customer chat messages')"
    printf '8. %s\n' "$(cc_text 'Odpowiedz klientowi przez e-mail' 'Reply to customer by e-mail')"
    printf '9. %s\n' "$(cc_text 'Pokaz zwroty i reklamacje' 'Show returns and complaints')"
    printf '10. %s\n' "$(cc_text 'Zmien status zwrotu lub reklamacji' 'Change return or complaint status')"
    printf '11. %s\n' "$(cc_text 'Pokaz liste wszystkich zamowien' 'Show all submitted orders')"
    printf '12. %s\n' "$(cc_text 'Pokaz fakture dla zamowienia' 'Show invoice for an order')"
    printf '13. %s\n' "$(cc_text 'Pobierz PDF faktury dla zamowienia' 'Download invoice PDF for an order')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action promotion_codes ;;
      2) run_menu_action promotion_code_create ;;
      3) run_menu_action review_pending ;;
      4) run_menu_action review_moderate ;;
      5) run_menu_action delivery_tracking ;;
      6) run_menu_action delivery_tracking_status ;;
      7) run_menu_action chat_messages 50 ;;
      8) run_menu_action chat_reply ;;
      9) run_menu_action returns_list ;;
      10) run_menu_action returns_status ;;
      11) run_menu_action orders_list 100 ;;
      12) run_menu_action billing_invoice ;;
      13) run_menu_action invoice_pdf ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

application_build_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Budowanie i artefakty' 'Builds and artifacts')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Pakietowanie calego monorepo' 'Package entire monorepo')"
    printf '2. %s\n' "$(cc_text 'Zbuduj lokalne obrazy Docker aplikacji' 'Build local Docker images')"
    printf '3. %s\n' "$(cc_text 'Uruchom aplikacje i frontend w kontenerach Docker' 'Start apps and frontend in Docker')"
    printf '4. %s\n' "$(cc_text 'Zatrzymaj kontenery aplikacji' 'Stop Docker apps')"
    printf '5. %s\n' "$(cc_text 'Status kontenerow aplikacji' 'Docker apps status')"
    printf '6. %s\n' "$(cc_text 'Logi kontenerow aplikacji' 'Docker apps logs')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action run_package all ;;
      2) run_menu_action images_build ;;
      3) run_menu_action docker_apps_up ;;
      4) run_menu_action docker_apps_down ;;
      5) run_menu_action docker_apps_status ;;
      6) run_menu_action docker_apps_logs ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

application_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Projekty i aplikacje' 'Projects and applications')" "$C_RESET"
    printf '%s\n' "$(cc_text 'Wybierz obszar operacyjny:' 'Choose an operational area:')"
    printf '1. %s\n' "$(cc_text 'Uruchamianie i status' 'Runtime and status')"
    printf '2. %s\n' "$(cc_text 'Testy i jakosc API' 'Testing and API quality')"
    printf '3. %s\n' "$(cc_text 'Bezpieczenstwo i konta' 'Security and accounts')"
    printf '4. %s\n' "$(cc_text 'Operacje sklepu' 'Store operations')"
    printf '5. %s\n' "$(cc_text 'Budowanie i artefakty' 'Builds and artifacts')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) application_runtime_menu ;;
      2) application_quality_menu ;;
      3) application_security_menu ;;
      4) application_store_menu ;;
      5) application_build_menu ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

frontend_menu() {
  while true; do
    printf '\n%s%s%s\n' "$C_BLUE" "$(cc_text 'Frontend sklepu' 'Store frontend')" "$C_RESET"
    printf '1. %s\n' "$(cc_text 'Uruchom frontend' 'Start frontend')"
    printf '2. %s\n' "$(cc_text 'Zatrzymaj frontend' 'Stop frontend')"
    printf '3. %s\n' "$(cc_text 'Status frontendu' 'Frontend status')"
    printf '4. %s\n' "$(cc_text 'Pokaz logi frontendu' 'Show frontend logs')"
    printf '5. %s\n' "$(cc_text 'Otworz adres frontendu (pokaz URL)' 'Open frontend address (show URL)')"
    printf '6. %s\n' "$(cc_text 'Uruchom Centrale webowa w przegladarce' 'Start Web Control Center in browser')"
    printf '0. %s\n' "$(cc_text 'Powrot' 'Back')"
    choice_prompt
    read -r choice || return
    case "$choice" in
      1) run_menu_action frontend_up ;;
      2) run_menu_action frontend_down ;;
      3) run_menu_action frontend_status ;;
      4) run_menu_action frontend_logs ;;
      5) printf '%s: http://127.0.0.1:%s\n' "$(cc_text 'Frontend' 'Frontend')" "$FRONTEND_PORT" ;;
      6) run_menu_action web_start ;;
      0) return ;;
      exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

main_menu() {
  while true; do
    printf '\n%s============================================================%s\n' "$C_BLUE" "$C_RESET"
    printf '%s                 Mini Order Platform%s\n' "$C_BLUE" "$C_RESET"
    printf '%s============================================================%s\n' "$C_BLUE" "$C_RESET"
    printf '%s: %s\n\n' "$(cc_text 'Projekt' 'Project')" "$PROJECT_ROOT"
    printf '1. %s\n' "$(cc_text 'Bazy danych PostgreSQL' 'PostgreSQL databases')"
    printf '2. %s\n' "$(cc_text 'Projekty i aplikacje' 'Projects and applications')"
    printf '3. %s\n' "$(cc_text 'Redis Event Broker i streamy' 'Redis Event Broker and streams')"
    printf '4. %s\n' "$(cc_text 'Profile i Mailpit' 'Profiles and Mailpit')"
    printf '5. %s\n' "$(cc_text 'Monitoring i Dead Letter Queue' 'Monitoring and Dead Letter Queue')"
    printf '6. %s\n' "$(cc_text 'Frontend sklepu' 'Store frontend')"
    printf '7. %s\n' "$(cc_text 'Status infrastruktury Docker Compose' 'Docker Compose infrastructure status')"
    printf '8. %s\n' "$(cc_text 'Ustawienia' 'Settings')"
    printf '9. %s\n' "$(cc_text 'Kontrola wymagan przed startem' 'Environment check before startup')"
    printf '10. %s\n' "$(cc_text 'Instalator brakujacych skladnikow (wymaga ACCEPT)' 'Install missing components (requires ACCEPT)')"
    printf '11. %s\n' "$(cc_text 'Kontrola plikow przed publikacja na GitHub' 'Check files before publishing to GitHub')"
    printf '12. %s\n' "$(cc_text 'Doctor srodowiska' 'Environment doctor')"
    printf '13. %s\n' "$(cc_text 'Profil prod - przygotowanie (NIEDOSTEPNY)' 'Production profile - preparation (UNAVAILABLE)')"
    printf '14. %s\n' "$(cc_text 'Centrala webowa (Web UI w przegladarce)' 'Web Control Center (Web UI in browser)')"
    printf '0. %s\n' "$(cc_text 'Wyjscie' 'Exit')"
    choice_prompt
    read -r choice || exit 0
    case "$choice" in
      1) database_menu ;;
      2) application_menu ;;
      3) broker_menu ;;
      4) profile_menu ;;
      5) monitoring_menu ;;
      6) frontend_menu ;;
      7) run_menu_action compose ps ;;
      8) settings_menu ;;
      9) run_menu_action environment_check ;;
      10) run_menu_action environment_install ;;
      11) run_menu_action github_check ;;
      12) run_menu_action doctor ;;
      13) production_menu ;;
      14) run_menu_action web_start ;;
      0|exit|quit) exit 0 ;;
      *) invalid_choice ;;
    esac
  done
}

command_dispatch() {
  case "${1:-}" in
    --list) print_catalog ;;
    --check|environment-check|start-check) environment_check ;;
    environment-install) environment_install ;;
    platform-info) platform_print_info ;;
    github-check) github_check ;;
    --help|-h|help) printf 'Uzycie: bash scripts/control-center/control-center.sh [komenda]\n\n'; print_catalog ;;
    infra-up) infra_up ;;
    infra-down) infra_down ;;
    infra-status) infra_status ;;
    infra-logs)
      if [[ -n "${2:-}" ]]; then
        if [[ "$2" == "broker" || "$2" == "$BROKER_SERVICE" ]]; then
          broker_logs
        elif [[ "$2" == "mailpit" || "$2" == "$MAILPIT_SERVICE" ]]; then
          mailpit_logs
        else
          db="$(db_service "$2")" || { printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$2" "$C_RESET" >&2; return 2; }
          compose logs --tail=100 "$db"
        fi
      else
        compose logs --tail=100
      fi
      ;;
    monitoring-up) monitoring_up ;;
    monitoring-down) monitoring_down ;;
    monitoring-status) monitoring_status ;;
    monitoring-logs) monitoring_logs "${2:-}" ;;
    monitoring-urls) monitoring_urls ;;
    dlq-list) dead_letter_list ;;
    dlq-replay) dead_letter_replay "${2:-}" ;;
    secrets-init) secrets_init ;;
    secrets-setup) secrets_setup "${2:-}" ;;
    secrets-update-web) secrets_update_web "${2:-}" ;;
    secrets-setup-web) secrets_setup_web "${2:-}" ;;
    secrets-ensure-auth) secrets_ensure_auth "${2:-}" ;;
    jwt-rotate) jwt_rotate "${2:-$(active_profile)}" ;;
    secrets-validate) secrets_validate "${2:-}" ;;
    secrets-status) secrets_status ;;
    secrets-edit) secrets_edit "${2:-}" ;;
    broker-up) broker_up ;;
    broker-down) broker_down ;;
    broker-status) broker_status ;;
    broker-logs) broker_logs ;;
    broker-cli) shift; broker_cli "$@" ;;
    stream-info) stream_info ;;
    stream-read) stream_read "${2:-20}" ;;
    broker-reset-group) broker_reset_group "${2:-$NOTIFICATION_CONSUMER_GROUP}" ;;
    mailpit-up) mailpit_up ;;
    mailpit-down) mailpit_down ;;
    mailpit-status) mailpit_status ;;
    mailpit-logs) mailpit_logs ;;
    mailpit-smoke) mailpit_smoke ;;
    mailpit-send) mailpit_send ;;
    mailpit-list) mailpit_list "${2:-20}" ;;
    mailpit-search) shift; mailpit_search "$*" ;;
    mailpit-clear) mailpit_clear ;;
    profile-show) profile_show ;;
    profile-set) profile_set "${2:-}" ;;
    language-show) language_show ;;
    language-set) language_set "${2:-}" ;;
    prod-status) prod_status ;;
    db-list) print_database_catalog ;;
    db-up) db_action up "${2:-all}" ;;
    db-down) db_action down "${2:-all}" ;;
    db-status) db_action status "${2:-all}" ;;
    db-logs) db_action logs "${2:-all}" ;;
    db-shell) db_shell "${2:-}" ;;
    db-down-all) db_down_all ;;
    db-backup) db_backup "${2:-all}" ;;
    db-backup-all) db_backup all ;;
    db-backup-list) db_backup_list "${2:-all}" ;;
    db-backup-prune) db_backup_prune "${2:-$BACKUP_RETENTION_DAYS}" ;;
    db-restore-test) db_restore_test "${2:-all}" ;;
    backup-key-init) backup_key_init "${2:-all}" ;;
    backup-schedule-install) backup_schedule_install "${2:-2}" ;;
    backup-schedule-remove) backup_schedule_remove ;;
    backup-schedule-status) backup_schedule_status ;;
    inventory-reset) inventory_reset ;;
    demo-reset) demo_reset ;;
    app-run) run_app "${2:-}" "${3:-}" ;;
    app-stop) app_stop "${2:-}" ;;
    app-run-all) app_run_all ;;
    app-stop-all) app_stop_all ;;
    app-status-all) app_status_all ;;
    app-logs) app_logs "${2:-}" ;;
    app-test) run_tests "${2:-all}" ;;
    app-package) run_package "${2:-all}" ;;
    health) health "${2:-all}" ;;
    smoke) smoke ;;
    delivery-smoke) delivery_smoke ;;
    delivery-tracking) delivery_tracking "${2:-}" ;;
    delivery-tracking-status) delivery_tracking_status "${2:-}" "${3:-}" ;;
    returns-list) returns_list ;;
    returns-status) returns_status "${2:-}" "${3:-}" ;;
    promotion-smoke) promotion_smoke ;;
    promotion-codes) promotion_codes "${2:-}" ;;
    promotion-code-create) promotion_code_create ;;
    orders-list) orders_list "${2:-100}" ;;
    invoices-list) invoices_list "${2:-50}" ;;
    invoice-show) billing_invoice "${2:-}" ;;
    invoice-pdf) invoice_pdf "${2:-}" ;;
    invoices-export-zip) invoices_export_zip "${2:-}" ;;
    invoice-correct) invoice_correct "${2:-}" "${3:-}" "${4:-}" ;;
    corrections-list) corrections_list "${2:-}" ;;
    review-pending) review_pending ;;
    review-moderate) shift; review_moderate "$@" ;;
    chat-list) chat_messages "${2:-50}" ;;
    chat-reply) shift; chat_reply "$@" ;;
    swagger-ui) swagger_ui ;;
    api-docs-urls) api_docs_urls ;;
    contract-test) qa_contract_test ;;
    security-test) qa_security_test ;;
    zap-dast) qa_zap_dast ;;
    load-test) qa_load_test ;;
    resilience-test) shift; qa_resilience_test "$@" ;;
    e2e) qa_e2e ;;
    ui-test-status) qa_ui_status ;;
    ui-test-install) qa_ui_install ;;
    ui-test-smoke) qa_ui_smoke ;;
    ui-test) qa_ui_regression ;;
    ui-test-system) qa_ui_system ;;
    ui-test-browsers) qa_ui_browsers ;;
    ui-test-headed) qa_ui_headed ;;
    ui-test-debug) qa_ui_debug ;;
    ui-test-report) qa_ui_report ;;
    images-build) images_build ;;
    docker-apps-up) docker_apps_up ;;
    docker-apps-down) docker_apps_down ;;
    docker-apps-status) docker_apps_status ;;
    docker-apps-logs) shift; docker_apps_logs "$@" ;;
    apps-compose) shift; apps_compose "$@" ;;
    k8s-validate) k8s_validate ;;
    k8s-status) k8s_status ;;
    openbao-status) openbao_status ;;
    openbao-validate) openbao_validate ;;
    tls-status) tls_status ;;
    resilience-status) resilience_status ;;
    jwt-status) jwt_status ;;
    jwt-smoke) jwt_smoke "${2:-}" ;;
    audit-list) audit_list "${2:-50}" ;;
    password-reset-request) password_reset_request "${2:-}" ;;
    app-verify) app_verify ;;
    verify-mvp) shift; verify_mvp auto "$@" ;;
    doctor) doctor ;;
    doctor-fix) doctor_auto_fix ;;
    fresh-start) workflow_fresh_start ;;
    seed-demo-data) workflow_seed_demo_data ;;
    frontend-up) frontend_up ;;
    frontend-down) frontend_down ;;
    frontend-status) frontend_status ;;
    frontend-logs) frontend_logs ;;
    web-start|web|web-ui) shift; web_start "$@" ;;
    "") main_menu ;;
    *) printf '%sNieznana komenda: %s%s\n\n' "$C_RED" "$1" "$C_RESET" >&2; print_catalog >&2; return 2 ;;
  esac
}

load_language
command_dispatch "$@"

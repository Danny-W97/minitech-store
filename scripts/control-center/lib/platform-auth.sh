#!/usr/bin/env bash
# Included by secrets.sh. Credentials remain inside the encrypted profile.
ensure_platform_auth() {
  local profile="$1" plain encrypted key recipient staged changed
  shift
  secret_profile_supported "$profile" || return 1
  plain="$(decrypt_profile_to_temp "$profile")" || return 1
  if ! changed="$(python3 "$PROJECT_ROOT/scripts/security/credentials.py" "$plain" "$@" --report-change)"; then
    rm -f -- "$plain"
    return 1
  fi
  if [[ "$changed" == unchanged ]]; then rm -f -- "$plain"; return 0; fi
  key="$(secret_key_file "$profile")"
  encrypted="$(secret_encrypted_file "$profile")"
  recipient="$(age-keygen -y "$key")" || { rm -f -- "$plain"; return 1; }
  staged="$(mktemp "$(dirname -- "$encrypted")/.mini-order-platform-auth.XXXXXX")" || { rm -f -- "$plain"; return 1; }
  chmod 600 "$staged"
  if SOPS_AGE_KEY_FILE="$key" sops --encrypt --input-type dotenv --output-type dotenv --age "$recipient" "$plain" > "$staged"; then
    if ! mv -f -- "$staged" "$encrypted"; then rm -f -- "$plain" "$staged"; return 1; fi
    rm -f -- "$plain"
  else
    rm -f -- "$plain" "$staged"
    return 1
  fi
}

configure_service_auth() {
  local project="$1" variable
  unset QA_OPERATOR_TOKEN
  if [[ "$project" != customer-service ]]; then
    unset CUSTOMER_JWT_SECRET CUSTOMER_JWT_PRIVATE_KEY_BASE64
    local secret_variable
    for secret_variable in ${!CUSTOMER_MFA_@}; do unset "$secret_variable"; done
  fi
  if [[ "$project" == api-gateway ]]; then
    unset PLATFORM_SERVICE_TOKEN PLATFORM_OPERATOR_TOKEN PLATFORM_SERVICE_CREDENTIALS_JSON
  else
    variable="PLATFORM_${project^^}_TOKEN"
    variable="${variable//-/_}"
    export PLATFORM_SERVICE_TOKEN="${!variable:-}"
    [[ -n "$PLATFORM_SERVICE_TOKEN" ]] || { printf 'Brak tozsamosci uslugi %s.\n' "$project" >&2; return 1; }
  fi
  if [[ "$project" != customer-service ]]; then
    unset PLATFORM_OPERATOR_TOKEN PLATFORM_SERVICE_CREDENTIALS_JSON
  fi
  # Keep only this service's credential in the child process environment.
  local candidate
  for candidate in ${!PLATFORM_@}; do
    [[ "$candidate" == PLATFORM_*_SERVICE_TOKEN ]] && unset "$candidate"
  done
  return 0
}

operator_curl() (
  local argument platform_url=false
  for argument in "$@"; do
    if [[ "$argument" =~ ^http://(127\.0\.0\.1|localhost):180(80|81|82|83|84|86|87|88|89|90|91|92|93|94|95)/ ]]; then platform_url=true; fi
    [[ "$argument" == --location || "$argument" == -L ]] && { printf 'Operator requests cannot follow redirects.\n' >&2; exit 2; }
  done
  if [[ "$platform_url" != true ]]; then command curl "$@"; exit $?; fi
  if [[ -n "${QA_OPERATOR_TOKEN:-}" ]]; then
    PLATFORM_OPERATOR_TOKEN="$QA_OPERATOR_TOKEN"
  else
    load_profile_secrets "$(active_profile)" || exit 1
  fi
  local header_file
  header_file="$(mktemp "${TMPDIR:-/tmp}/mini-order-platform-operator.XXXXXX")" || exit 1
  chmod 600 "$header_file"
  trap 'rm -f -- "$header_file"' EXIT
  printf 'Authorization: Bearer %s\n' "$PLATFORM_OPERATOR_TOKEN" > "$header_file"
  # Do not follow redirects with the operator credential.
  command curl --header "@$header_file" "$@"
)

#!/usr/bin/env bash
set -Euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="${1:-dev}"
CLEANED_UP=0

case "$PROFILE" in
  dev|test) ;;
  *)
    printf 'Profil IDE musi byc dev albo test.\n' >&2
    exit 2
    ;;
esac

cleanup() {
  if [[ "$CLEANED_UP" -eq 0 ]]; then
    CLEANED_UP=1
    printf '\nZatrzymuje serwisy uruchomione przez srodowisko IntelliJ...\n'
    bash "$SCRIPT_DIR/control-center.sh" app-stop-all || true
  fi
}

trap cleanup EXIT INT TERM

if ! bash "$SCRIPT_DIR/control-center.sh" profile-set "$PROFILE"; then
  exit 1
fi
printf 'Sprawdzam sekrety profilu %s przed uruchomieniem Compose...\n' "$PROFILE"
if ! bash "$SCRIPT_DIR/control-center.sh" secrets-validate "$PROFILE"; then
  printf 'Brakuje wymaganego sekretu. Uzupełnij go poleceniem: bash scripts/control-center/control-center.sh secrets-edit %s\n' "$PROFILE" >&2
  exit 1
fi
printf 'Uruchamiam infrastrukture lokalna (bazy danych, Redis i Mailpit)...\n'
if ! bash "$SCRIPT_DIR/control-center.sh" infra-up; then
  printf 'Nie udalo sie uruchomic infrastruktury lokalnej.\n' >&2
  exit 1
fi
if ! bash "$SCRIPT_DIR/control-center.sh" app-run-all; then
  printf 'Nie udalo sie uruchomic aplikacji.\n' >&2
  exit 1
fi

wait_for_apps() {
  local elapsed=0
  local timeout=180
  printf '\nCzekam na gotowosc endpointow (maks. %ss)...\n' "$timeout"
  while (( elapsed < timeout )); do
    if bash "$SCRIPT_DIR/control-center.sh" health all >/dev/null 2>&1; then
      printf 'Wszystkie endpointy sa gotowe.\n'
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done

  printf 'Nie wszystkie endpointy wystartowaly w czasie %ss.\n' "$timeout" >&2
  bash "$SCRIPT_DIR/control-center.sh" app-status-all || true
  printf 'Sprawdz logi: bash scripts/control-center/control-center.sh app-logs <service>\n' >&2
  return 1
}

wait_for_apps || exit 1

printf '\nMiniTechStore dziala z profilem %s.\n' "$PROFILE"
printf 'Health: bash scripts/control-center/control-center.sh health all\n'
printf 'Zatrzymanie: czerwony przycisk Stop w IntelliJ albo Ctrl+C.\n'

while true; do
  sleep 2
done

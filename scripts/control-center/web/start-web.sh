#!/usr/bin/env bash
set -Euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd)"
HOST="127.0.0.1"
PORT="${MINITECH_CONTROL_PORT:-18100}"
OPEN_BROWSER="${CONTROL_CENTER_NO_BROWSER:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-browser) OPEN_BROWSER=1 ;;
    --port) shift; PORT="${1:?Brak numeru portu po --port}" ;;
    -h|--help)
      printf 'Uzycie: bash start-web.sh [--port <PORT>] [--no-browser]\n'
      exit 0
      ;;
    *) printf 'Nieznany argument panelu: %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

command -v python3 >/dev/null 2>&1 || {
  printf 'Brak python3. Uruchom: bash scripts/control-center/control-center.sh environment-check\n' >&2
  exit 1
}

URL="http://${HOST}:${PORT}"
printf 'Uruchamiam MiniTech Control: %s\n' "$URL"
python3 "$SCRIPT_DIR/server.py" --host "$HOST" --port "$PORT" &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT INT TERM

ready=0
for _ in {1..40}; do
  if command -v curl >/dev/null 2>&1 && curl --silent --fail "$URL/api/ping" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    wait "$SERVER_PID"
    exit $?
  fi
  sleep 0.25
done

[[ "$ready" == "1" ]] || {
  printf 'Panel nie odpowiedzial na porcie %s.\n' "$PORT" >&2
  exit 1
}

if [[ "$OPEN_BROWSER" != "1" ]]; then
  if command -v wslview >/dev/null 2>&1; then
    wslview "$URL" >/dev/null 2>&1 &
  elif [[ -x /mnt/c/Windows/explorer.exe ]]; then
    /mnt/c/Windows/explorer.exe "$URL" >/dev/null 2>&1 &
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$URL" >/dev/null 2>&1 &
  elif command -v open >/dev/null 2>&1; then
    open "$URL" >/dev/null 2>&1 &
  else
    printf 'Otworz adres recznie: %s\n' "$URL"
  fi
fi

printf 'Panel dziala. Ctrl+C zatrzymuje lokalny serwer Centrali.\n'
wait "$SERVER_PID"

#!/usr/bin/env bash

# Detect the host/runtime before control-center.sh initializes paths or
# platform-specific helpers. Set MINI_ORDER_PLATFORM=macos|linux|wsl|windows
# to override detection for diagnostics.

PLATFORM_UNAME="$(uname -s 2>/dev/null || printf 'unknown')"
PLATFORM_ARCH="$(uname -m 2>/dev/null || printf 'unknown')"

platform_detect_id() {
  local override="${MINI_ORDER_PLATFORM:-auto}"
  if [[ "$override" != 'auto' ]]; then
    case "$override" in
      macos|linux|wsl|windows) printf '%s' "$override"; return 0 ;;
      *)
        printf 'Nieznana wartosc MINI_ORDER_PLATFORM: %s\n' "$override" >&2
        return 2
        ;;
    esac
  fi

  case "$PLATFORM_UNAME" in
    Darwin) printf 'macos' ;;
    Linux)
      if [[ -n "${WSL_INTEROP:-}" || -n "${WSL_DISTRO_NAME:-}" ]] \
        || { [[ -r /proc/version ]] && grep -qiE 'microsoft|wsl' /proc/version; }; then
        printf 'wsl'
      else
        printf 'linux'
      fi
      ;;
    MINGW*|MSYS*|CYGWIN*) printf 'windows' ;;
    *)
      printf 'Nieobslugiwany system: %s\n' "$PLATFORM_UNAME" >&2
      return 1
      ;;
  esac
}

PLATFORM_ID="$(platform_detect_id)" || exit $?
PLATFORM_ADAPTER="$SCRIPT_DIR/platform-${PLATFORM_ID}.sh"

if [[ ! -f "$PLATFORM_ADAPTER" ]]; then
  printf 'Brak adaptera platformy: %s\n' "$PLATFORM_ADAPTER" >&2
  return 1 2>/dev/null || exit 1
fi

# shellcheck source=/dev/null
source "$SCRIPT_DIR/platform-common.sh"
# shellcheck source=/dev/null
source "$PLATFORM_ADAPTER"

PLATFORM_LABEL="${PLATFORM_LABEL:-$PLATFORM_ID}"

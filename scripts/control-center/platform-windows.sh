#!/usr/bin/env bash

PLATFORM_LABEL='Windows (Git Bash/MSYS)'

platform_config_directory() {
  if [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
    printf '%s' "$XDG_CONFIG_HOME"
  elif [[ -n "${APPDATA:-}" ]]; then
    if command -v cygpath >/dev/null 2>&1; then
      printf '%s/mini-order-platform' "$(cygpath -u "$APPDATA")"
    else
      printf '%s/.config' "$(platform_home_directory)"
    fi
  else
    printf '%s/.config' "$(platform_home_directory)"
  fi
}

platform_scheduler_name() {
  printf 'crontab (Git Bash; zalecany launcher PowerShell/WSL)'
}

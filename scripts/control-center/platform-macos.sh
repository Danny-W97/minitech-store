#!/usr/bin/env bash

PLATFORM_LABEL='macOS'

platform_config_directory() {
  if [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
    printf '%s' "$XDG_CONFIG_HOME"
    return
  fi

  local home legacy_config native_config
  home="$(platform_home_directory)"
  legacy_config="$home/.config"
  native_config="$home/Library/Application Support"

  # Keep an existing .config installation usable; new macOS setups use the
  # native Application Support location.
  if [[ -e "$legacy_config/mini-order-platform" ]]; then
    printf '%s' "$legacy_config"
  else
    printf '%s' "$native_config"
  fi
}

platform_scheduler_name() {
  printf 'launchd'
}

platform_java_install_hint() {
  printf 'Zainstaluj JDK 25 (np. Temurin/Homebrew) i upewnij sie, ze java oraz javac sa w PATH.'
}

platform_python_install_hint() {
  printf 'Zainstaluj Python 3 (np. python.org/Homebrew) i upewnij sie, ze python3 jest w PATH.'
}

platform_backup_schedule_label() {
  printf 'com.minitechstore.mini-order-platform.postgres-backup'
}

platform_backup_schedule_plist() {
  printf '%s/Library/LaunchAgents/%s.plist' \
    "$(platform_home_directory)" "$(platform_backup_schedule_label)"
}

platform_backup_schedule_install() {
  local hour="$1"
  local backup_script="$2"
  local log_file="$3"
  local plist_file
  local launch_domain
  plist_file="$(platform_backup_schedule_plist)"
  launch_domain="gui/$(id -u)"

  require_command launchctl || return 1
  require_command python3 || return 1
  mkdir -p "$(dirname "$plist_file")" "$(dirname "$log_file")"

  PLATFORM_LAUNCHD_PLIST="$plist_file" \
  PLATFORM_LAUNCHD_LABEL="$(platform_backup_schedule_label)" \
  PLATFORM_LAUNCHD_SCRIPT="$backup_script" \
  PLATFORM_LAUNCHD_LOG="$log_file" \
  PLATFORM_LAUNCHD_HOUR="$hour" \
  python3 - <<'PY'
import os
import plistlib

plist = {
    "Label": os.environ["PLATFORM_LAUNCHD_LABEL"],
    "ProgramArguments": ["/bin/bash", os.environ["PLATFORM_LAUNCHD_SCRIPT"]],
    "WorkingDirectory": os.path.dirname(os.environ["PLATFORM_LAUNCHD_SCRIPT"]),
    "StartCalendarInterval": {
        "Hour": int(os.environ["PLATFORM_LAUNCHD_HOUR"]),
        "Minute": 0,
    },
    "StandardOutPath": os.environ["PLATFORM_LAUNCHD_LOG"],
    "StandardErrorPath": os.environ["PLATFORM_LAUNCHD_LOG"],
    "EnvironmentVariables": {
        "PATH": os.environ.get("PATH", "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin")
    },
}

with open(os.environ["PLATFORM_LAUNCHD_PLIST"], "wb") as stream:
    plistlib.dump(plist, stream, fmt=plistlib.FMT_XML, sort_keys=False)
PY

  launchctl bootout "$launch_domain" "$plist_file" >/dev/null 2>&1 || true
  launchctl bootstrap "$launch_domain" "$plist_file" || return 1
  printf '%sAutomatyczny backup PostgreSQL ustawiony przez launchd codziennie o %02d:00.%s\n' \
    "$C_GREEN" "$hour" "$C_RESET"
}

platform_backup_schedule_remove() {
  local plist_file launch_domain
  plist_file="$(platform_backup_schedule_plist)"
  launch_domain="gui/$(id -u)"
  require_command launchctl || return 1
  launchctl bootout "$launch_domain" "$plist_file" >/dev/null 2>&1 || true
  rm -f -- "$plist_file"
  printf 'Automatyczny backup PostgreSQL zostal usuniety z launchd.\n'
}

platform_backup_schedule_status() {
  local label
  label="$(platform_backup_schedule_label)"
  require_command launchctl || return 1
  if launchctl print "gui/$(id -u)/$label" >/dev/null 2>&1; then
    printf 'Aktywny harmonogram backupu PostgreSQL: launchd/%s\n' "$label"
  else
    printf 'Brak aktywnego harmonogramu backupu PostgreSQL w launchd.\n'
  fi
}

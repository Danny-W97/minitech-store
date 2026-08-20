#!/usr/bin/env bash

# Shared platform adapter functions. This file is sourced by platform.sh.

platform_home_directory() {
  if [[ -n "${HOME:-}" ]]; then
    printf '%s' "$HOME"
  else
    pwd
  fi
}

platform_config_directory() {
  if [[ -n "${XDG_CONFIG_HOME:-}" ]]; then
    printf '%s' "$XDG_CONFIG_HOME"
  else
    printf '%s/.config' "$(platform_home_directory)"
  fi
}

platform_base64_stdin() {
  # GNU base64 supports -w, while BSD base64 (macOS) does not.
  base64 | tr -d '\r\n'
}

platform_base64_decode_stdin() {
  # GNU coreutils uses --decode, while BSD base64 (macOS) uses -D.
  if base64 --decode </dev/null >/dev/null 2>&1; then
    base64 --decode
  else
    base64 -D
  fi
}

platform_base64_file() {
  platform_base64_stdin < "$1"
}

platform_lowercase() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

platform_sed_in_place() {
  local file="$1"
  local expression="$2"
  if [[ "${PLATFORM_ID:-}" == 'macos' ]]; then
    sed -i '' -E "$expression" "$file"
  else
    sed -i -E "$expression" "$file"
  fi
}

platform_scheduler_name() {
  printf 'crontab'
}

platform_java_install_hint() {
  printf 'Zainstaluj JDK 25 i upewnij sie, ze java oraz javac sa w PATH.'
}

platform_python_install_hint() {
  printf 'Zainstaluj Python 3 i upewnij sie, ze python3 jest w PATH.'
}

platform_package_manager() {
  case "${PLATFORM_ID:-}" in
    macos)
      if command -v brew >/dev/null 2>&1; then
        printf 'brew'
      elif command -v port >/dev/null 2>&1; then
        printf 'port'
      else
        printf 'none'
      fi
      ;;
    linux|wsl)
      if command -v apt-get >/dev/null 2>&1; then
        printf 'apt-get'
      elif command -v dnf >/dev/null 2>&1; then
        printf 'dnf'
      elif command -v yum >/dev/null 2>&1; then
        printf 'yum'
      elif command -v pacman >/dev/null 2>&1; then
        printf 'pacman'
      elif command -v zypper >/dev/null 2>&1; then
        printf 'zypper'
      else
        printf 'none'
      fi
      ;;
    windows)
      printf 'unsupported'
      ;;
    *)
      printf 'none'
      ;;
  esac
}

platform_install_target() {
  case "${PLATFORM_ID:-}" in
    wsl)
      printf 'wewnatrz dystrybucji WSL, przez jej menedzer pakietow; poza repozytorium projektu'
      ;;
    linux)
      printf 'w systemie Linux, przez wykryty menedzer pakietow; poza repozytorium projektu'
      ;;
    macos)
      printf 'w systemie macOS, w prefiksie Homebrew/MacPorts; poza repozytorium projektu'
      ;;
    windows)
      printf 'brak — uruchom Control Center wewnatrz WSL'
      ;;
    *)
      printf 'nieznane srodowisko'
      ;;
  esac
}

platform_install_package_name() {
  local manager="$1"
  local requirement="$2"
  case "$manager:$requirement" in
    apt-get:bash|dnf:bash|yum:bash|pacman:bash|zypper:bash) printf 'bash' ;;
    apt-get:jdk25) printf 'openjdk-25-jdk' ;;
    dnf:jdk25|yum:jdk25) printf 'java-25-openjdk-devel' ;;
    pacman:jdk25|zypper:jdk25) printf 'jdk-openjdk' ;;
    apt-get:python3|dnf:python3|yum:python3|zypper:python3) printf 'python3' ;;
    pacman:python3) printf 'python' ;;
    apt-get:curl|dnf:curl|yum:curl|pacman:curl|zypper:curl) printf 'curl' ;;
    apt-get:git|dnf:git|yum:git|pacman:git|zypper:git) printf 'git' ;;
    apt-get:sops|dnf:sops|yum:sops|pacman:sops|zypper:sops) printf 'sops' ;;
    apt-get:age|dnf:age|yum:age|pacman:age|zypper:age) printf 'age' ;;
    apt-get:docker) printf 'docker.io' ;;
    dnf:docker|yum:docker|pacman:docker|zypper:docker) printf 'docker' ;;
    apt-get:compose) printf 'docker-compose-plugin' ;;
    dnf:compose|yum:compose) printf 'docker-compose-plugin' ;;
    pacman:compose|zypper:compose) printf 'docker-compose' ;;
    brew:bash) printf 'bash' ;;
    brew:jdk25) printf 'openjdk@25' ;;
    brew:python3) printf 'python' ;;
    brew:curl|brew:git|brew:sops|brew:age|brew:docker) printf '%s' "${requirement}" ;;
    brew:compose) printf 'docker-compose' ;;
    port:bash) printf 'bash' ;;
    port:jdk25) printf 'openjdk25' ;;
    port:python3) printf 'python312' ;;
    port:curl|port:git|port:sops|port:age|port:docker|port:compose) printf '%s' "${requirement}" ;;
    *) return 1 ;;
  esac
}

platform_install_requirement_label() {
  case "$1" in
    bash) printf 'Bash' ;;
    jdk25) printf 'JDK 25 (java + javac)' ;;
    python3) printf 'Python 3' ;;
    curl) printf 'curl' ;;
    git) printf 'Git' ;;
    sops) printf 'SOPS' ;;
    age) printf 'age + age-keygen' ;;
    docker) printf 'Docker CLI' ;;
    compose) printf 'Docker Compose v2' ;;
    runtime) printf 'runtime dla linuksowych kontenerow' ;;
    *) printf '%s' "$1" ;;
  esac
}

platform_install_requirement_location() {
  case "$1" in
    jdk25) printf 'systemowy JDK; java i javac musza trafic do PATH' ;;
    docker|compose) printf 'Docker CLI/plugin poza repozytorium; daemon/runtime pozostaje osobno' ;;
    age) printf 'pakiet age; zawiera takze age-keygen' ;;
    *) printf '%s' "$(platform_install_target)" ;;
  esac
}

platform_run_as_root() {
  if [[ "$(id -u)" == '0' ]]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    printf 'Brak sudo. Uruchom polecenie jako root: %s\n' "$*" >&2
    return 1
  fi
}

platform_install_packages() {
  local manager="$1"
  shift
  local -a packages=()
  local requirement package existing duplicate

  for requirement in "$@"; do
    package="$(platform_install_package_name "$manager" "$requirement" 2>/dev/null || true)"
    if [[ -z "$package" ]]; then
      printf 'Brak bezpiecznego mapowania pakietu dla: %s\n' \
        "$(platform_install_requirement_label "$requirement")" >&2
      continue
    fi
    duplicate=false
    for existing in "${packages[@]}"; do
      [[ "$existing" == "$package" ]] && duplicate=true && break
    done
    [[ "$duplicate" == true ]] || packages+=("$package")
  done

  ((${#packages[@]} > 0)) || {
    printf 'Brak automatycznie instalowalnych pakietow dla tego srodowiska.\n' >&2
    return 2
  }

  case "$manager" in
    apt-get)
      platform_run_as_root apt-get update || return 1
      platform_run_as_root apt-get install -y "${packages[@]}"
      ;;
    dnf)
      platform_run_as_root dnf install -y "${packages[@]}"
      ;;
    yum)
      platform_run_as_root yum install -y "${packages[@]}"
      ;;
    pacman)
      platform_run_as_root pacman -S --needed --noconfirm "${packages[@]}"
      ;;
    zypper)
      platform_run_as_root zypper --non-interactive install "${packages[@]}"
      ;;
    brew)
      brew install "${packages[@]}"
      ;;
    port)
      platform_run_as_root port install "${packages[@]}"
      ;;
    *)
      printf 'Nieobslugiwany menedzer pakietow: %s\n' "$manager" >&2
      return 2
      ;;
  esac
}

platform_post_install_environment() {
  # Homebrew keeps versioned OpenJDK formulas keg-only. Make the newly
  # installed JDK available to this Control Center process and report the
  # same path to the user for a future shell session.
  if [[ "${PLATFORM_ID:-}" == 'macos' ]] && command -v brew >/dev/null 2>&1; then
    local jdk_prefix
    jdk_prefix="$(brew --prefix openjdk@25 2>/dev/null || true)"
    if [[ -x "$jdk_prefix/bin/java" ]]; then
      export JAVA_HOME="$jdk_prefix"
      export PATH="$jdk_prefix/bin:$PATH"
      printf 'JDK 25 dodany do PATH tej sesji: %s\n' "$jdk_prefix"
    fi
  fi
}

platform_print_info() {
  printf 'Wykryta platforma: %s (%s)\n' "${PLATFORM_ID:-unknown}" "${PLATFORM_LABEL:-unknown}"
  printf 'Architektura: %s\n' "${PLATFORM_ARCH:-unknown}"
  printf 'System: %s\n' "${PLATFORM_UNAME:-unknown}"
  printf 'Katalog konfiguracji: %s\n' "${CONFIG_DIRECTORY:-$(platform_config_directory)}"
  printf 'Harmonogram backupu: %s\n' "$(platform_scheduler_name)"
}

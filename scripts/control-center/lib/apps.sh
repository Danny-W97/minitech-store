#!/usr/bin/env bash
# Application runtime, background process control, logs, and frontend management

maven() {
  prepare_java_environment || return 1
  if [[ -f "$MAVEN_WRAPPER" ]]; then
    bash "$MAVEN_WRAPPER" "$@"
  else
    require_command mvn || return 1
    mvn "$@"
  fi
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
  maven -pl platform-security -am -DskipTests install || return 1
  configure_service_auth "$project" || return 1
  maven -pl "$project" spring-boot:run
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
      configure_service_auth "$project" || exit 1
      exec setsid bash "$MAVEN_WRAPPER" -pl "$project" spring-boot:run
    ) > "$log_file" 2>&1 &
  else
    (
      cd "$PROJECT_ROOT" || exit 1
      configure_service_auth "$project" || exit 1
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
  maven -pl platform-security -am -DskipTests install || return 1
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



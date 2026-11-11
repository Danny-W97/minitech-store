#!/usr/bin/env bash
# Database backup, encryption, retention, and restore testing

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
  local mode="${BACKUP_RETENTION_MODE:-observe}"
  [[ "$mode" == observe || "$mode" == purge ]] || return 2
  printf 'Retencja backupow (%s dni), tryb %s: %s\n'  "$retention_days" "$mode" "$profile_directory"
  # Complete platform sets have their own authenticated, whole-set retention.
  if [[ "$mode" == purge ]]; then
    find "$profile_directory" -type f -name '*.dump.age' ! -path "$profile_directory/checkpoints/*" -mtime "+$retention_days" -print -delete
  else
    find "$profile_directory" -type f -name '*.dump.age' ! -path "$profile_directory/checkpoints/*" -mtime "+$retention_days" -print
  fi
}


platform_backup() {
  require_command docker || return 1
  require_command python3 || return 1
  require_command age || return 1
  local profile alias service container broker
  profile="$(active_profile)"
  secret_profile_supported "$profile" || { printf 'Platform backup currently supports dev/test Compose only.\n' >&2; return 2; }
  backup_recipient_for_profile "$profile" || return 1
  backup_prepare_directory || return 1
  local -a rows=()
  for service in "${DB_SERVICES[@]}"; do
    alias="${service%-db}"
    container="$(compose ps -q "$service")" || return 1
    [[ "$container" =~ ^[a-f0-9]{12,64}$ ]] || { printf 'Brak jednej dzialajacej bazy: %s\n' "$service" >&2; return 1; }
    rows+=("$alias:$container:$(db_name "$alias"):$(db_user "$alias")")
  done
  broker="$(compose ps -q "$BROKER_SERVICE")" || return 1
  [[ "$broker" =~ ^[a-f0-9]{12,64}$ ]] || { printf 'Brak jednego dzialajacego brokera Redis.\n' >&2; return 1; }
  python3 -c 'import json,sys; rows=[v.split(":") for v in sys.argv[2:]]; print(json.dumps({"redis":{"container":sys.argv[1]},"databases":{a:{"container":c,"database":d,"user":u} for a,c,d,u in rows}}))' "$broker" "${rows[@]}" \
    | python3 "$PROJECT_ROOT/scripts/backup/platform-recovery.py" capture \
      --recipient "$BACKUP_RECIPIENT" --output-dir "$(backup_profile_directory "$profile")/checkpoints"
}


latest_platform_backup() {
  python3 -c 'from pathlib import Path; import sys; root=Path(sys.argv[1]); files=sorted((p for p in root.glob("checkpoint-*/manifest.json.age") if not p.is_symlink() and not p.parent.is_symlink()),key=lambda p:p.stat().st_mtime,reverse=True); sys.exit(1) if not files else print(files[0].parent)' \
    "$(backup_profile_directory "$(active_profile)")/checkpoints"
}


platform_restore_drill() {
  require_command docker || return 1
  require_command python3 || return 1
  require_command age || return 1
  local bundle="${1:-latest}" order_id="${2:-}" profile report
  profile="$(active_profile)"
  secret_profile_supported "$profile" || { printf 'Platform restore drill currently supports dev/test only.\n' >&2; return 2; }
  backup_recipient_for_profile "$profile" || return 1
  [[ -f "${BACKUP_IDENTITY_FILE:-}" ]] || return 1
  if [[ "$bundle" == 'latest' ]]; then
    bundle="$(latest_platform_backup)" || { printf 'Brak kompletnego zestawu kopii platformy.\n' >&2; return 1; }
  fi
  local -a witness=()
  if [[ -n "$order_id" ]]; then
    [[ "$order_id" =~ ^[1-9][0-9]*$ ]] || { printf 'ID zamowienia musi byc dodatnia liczba.\n' >&2; return 2; }
    witness=(--order-id "$order_id")
  else
    printf 'Proba techniczna bez wskazanego zamowienia: zgodnosc biznesowa nie bedzie potwierdzona.\n'
  fi
  report="$RUNTIME_DIRECTORY/recovery-drills/restore-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM.json"
  python3 "$PROJECT_ROOT/scripts/backup/platform-recovery.py" drill \
    --bundle "$bundle" --identity "$BACKUP_IDENTITY_FILE" --report "$report" "${witness[@]}"
}


platform_backup_prune() {
  require_command python3 || return 1
  require_command age || return 1
  backup_root_check || return 1
  local days="${1:-$BACKUP_RETENTION_DAYS}" profile directory
  [[ "$days" =~ ^[1-9][0-9]*$ ]] || return 2
  profile="$(active_profile)"
  secret_profile_supported "$profile" || return 2
  backup_recipient_for_profile "$profile" || return 1
  directory="$(backup_profile_directory "$profile")/checkpoints"
  [[ -d "$directory" ]] || { printf 'Brak zestawow do czyszczenia.\n'; return 0; }
  python3 "$PROJECT_ROOT/scripts/backup/platform-recovery.py" prune \
    --directory "$directory" --identity "$BACKUP_IDENTITY_FILE" --days "$days" --mode "${BACKUP_RETENTION_MODE:-observe}"
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




privacy_journal_config() {
  local name
  for name in PRIVACY_JOURNAL_DIRECTORY PRIVACY_JOURNAL_IDENTITY_FILE PRIVACY_JOURNAL_PUBLIC_KEY_FILE; do
    [[ -n "${!name:-}" ]] || { printf 'Brak konfiguracji rejestru: %s\n' "$name" >&2; return 2; }
  done
  require_command python3 && require_command age && require_command openssl
}

privacy_journal_sync() (
  privacy_journal_config || exit $?
  [[ -n "${PRIVACY_JOURNAL_SIGNING_KEY_FILE:-}" && -n "${PRIVACY_JOURNAL_AGE_RECIPIENT:-}" ]] || { printf 'Skonfiguruj osobny klucz podpisu i recipient rejestru.\n' >&2; exit 2; }
  load_profile_secrets "$(active_profile)" || exit 1
  python3 "$PROJECT_ROOT/scripts/backup/privacy-journal.py" sync \
    --directory "$PRIVACY_JOURNAL_DIRECTORY" --identity "$PRIVACY_JOURNAL_IDENTITY_FILE" \
    --public-key "$PRIVACY_JOURNAL_PUBLIC_KEY_FILE" --signing-key "$PRIVACY_JOURNAL_SIGNING_KEY_FILE" \
    --recipient "$PRIVACY_JOURNAL_AGE_RECIPIENT" --backup-root "$BACKUP_ROOT_DIRECTORY" \
    --origin "${PRIVACY_JOURNAL_CUSTOMER_ORIGIN:-http://127.0.0.1:18087}"
)

privacy_restore_drill() {
  privacy_journal_config || return $?
  local bundle="${1:-latest}" expected="${2:-}"
  [[ -n "$expected" ]] || { printf 'Wymagany aktualny wskaznik rejestru z niezaleznego miejsca.\n' >&2; return 2; }
  backup_recipient_for_profile "$(active_profile)" || return 1
  [[ "$bundle" != latest ]] || bundle="$(latest_platform_backup)" || return 1
  python3 "$PROJECT_ROOT/scripts/backup/privacy-restore.py" \
    --bundle "$bundle" --identity "$BACKUP_IDENTITY_FILE" --backup-root "$BACKUP_ROOT_DIRECTORY" \
    --journal-directory "$PRIVACY_JOURNAL_DIRECTORY" --journal-identity "$PRIVACY_JOURNAL_IDENTITY_FILE" \
    --journal-public-key "$PRIVACY_JOURNAL_PUBLIC_KEY_FILE" --expected-head "$expected" \
    --report "$RUNTIME_DIRECTORY/recovery-drills/privacy-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM.json"
}

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/control-center/lib/apps.sh

# Exercise launch orchestration with no real JVM, Docker, profile or credentials.
C_RED='' C_RESET='' C_BLUE=''
app_project() { printf 'chat-service\n'; }
active_profile() { printf 'test\n'; }
app_docker_service_name() { printf 'chat-service\n'; }
docker() { return 1; }
environment_check() { :; }
load_profile_secrets() { :; }
app_db_password_variable() { :; }
app_port() { printf '18093\n'; }
configure_service_auth() { :; }
calls=()
fail_install=false
maven() {
  calls+=("$*")
  if [[ "$*" == *install && "$fail_install" == true ]]; then return 1; fi
}

run_app chat >/dev/null
[[ "${#calls[@]}" == 2 ]]
[[ "${calls[0]}" == '-pl platform-security -am -DskipTests install' ]]
[[ "${calls[1]}" == '-pl chat-service spring-boot:run' ]]

calls=()
fail_install=true
if run_app chat >/dev/null; then
  printf 'Startup must stop when installing the shared parent/library fails\n' >&2
  exit 1
fi
[[ "${#calls[@]}" == 1 ]]
printf 'PASS Maven startup: parent/library first; install failure prevents launch\n'

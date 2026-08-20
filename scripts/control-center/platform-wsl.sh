#!/usr/bin/env bash

PLATFORM_LABEL='WSL/Linux'

platform_scheduler_name() {
  printf 'cron/crontab w WSL'
}

platform_java_install_hint() {
  printf 'Zainstaluj w WSL JDK 25 i upewnij sie, ze java oraz javac sa w PATH.'
}

platform_python_install_hint() {
  printf 'Zainstaluj w WSL Python 3 i upewnij sie, ze python3 jest w PATH.'
}

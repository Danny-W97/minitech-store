#!/usr/bin/env bash
set -Euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="$SCRIPT_DIR/lib"

# Load modules
source "$LIB_DIR/platform.sh"
source "$LIB_DIR/core.sh"
source "$LIB_DIR/secrets.sh"
source "$LIB_DIR/infra.sh"
source "$LIB_DIR/apps.sh"
source "$LIB_DIR/backups.sh"
source "$LIB_DIR/operations.sh"
source "$LIB_DIR/qa.sh"
source "$LIB_DIR/menus.sh"

command_dispatch() {
  case "${1:-}" in
    --list) print_catalog ;;
    --check|environment-check|start-check) environment_check ;;
    environment-install) environment_install ;;
    platform-info) platform_print_info ;;
    github-check) github_check ;;
    --help|-h|help) printf 'Uzycie: bash scripts/control-center/control-center.sh [komenda]\n\n'; print_catalog ;;
    infra-up) infra_up ;;
    infra-down) infra_down ;;
    infra-status) infra_status ;;
    infra-logs)
      if [[ -n "${2:-}" ]]; then
        if [[ "$2" == "broker" || "$2" == "$BROKER_SERVICE" ]]; then
          broker_logs
        elif [[ "$2" == "mailpit" || "$2" == "$MAILPIT_SERVICE" ]]; then
          mailpit_logs
        else
          db="$(db_service "$2")" || { printf '%sNieznana baza/alias: %s%s\n' "$C_RED" "$2" "$C_RESET" >&2; return 2; }
          compose logs --tail=100 "$db"
        fi
      else
        compose logs --tail=100
      fi
      ;;
    monitoring-up) monitoring_up ;;
    monitoring-check) monitoring_check ;;
    monitoring-alerts) monitoring_alerts ;;
    monitoring-down) monitoring_down ;;
    monitoring-status) monitoring_status ;;
    monitoring-logs) monitoring_logs "${2:-}" ;;
    monitoring-urls) monitoring_urls ;;
    dlq-list) dead_letter_list ;;
    dlq-replay) dead_letter_replay "${2:-}" ;;
    payment-follow-ups) payment_follow_up_list "${2:-DEAD_LETTER}" "${3:-50}" "${4:-0}" ;;
    payment-follow-up-replay) payment_follow_up_replay "${2:-}" ;;
    payment-collect) payment_collect "${2:-}" "${3:-}" "${4:-}" ;;
    payment-stripe-status) payment_stripe_status "${2:-}" ;;
    payment-reconcile) payment_stripe_reconcile "${2:-}" ;;
    delivery-sync) delivery_sync "${2:-}" ;;
    delivery-reconcile) delivery_sync "${2:-}" POST ;;
    payment-refunds) payment_refunds "${2:-}" ;;
    payment-refund) payment_refund "${2:-}" "${3:-}" "${4:-}" "${5:-CUSTOMER_REQUEST}" ;;
    payment-refund-reconcile) payment_refund_reconcile "${2:-}" ;;
    order-cancel) order_cancel "${2:-}" ;;
    order-sagas) order_sagas "${2:-RECONCILIATION_REQUIRED}" "${3:-50}" "${4:-0}" ;;
    order-reconcile) order_reconcile "${2:-}" ;;
    order-coupon) order_coupon "${2:-}" ;;
    order-coupon-reconcile) order_coupon "${2:-}" POST ;;
    coupon-capacity) coupon_capacity "${2:-}" ;;
    coupon-reservations) coupon_reservations "${2:-}" "${3:-RESERVED}" "${4:-50}" "${5:-0}" ;;
    inventory-receive) inventory_receive "${2:-}" "${3:-}" "${4:-}" ;;
    inventory-adjust) inventory_adjust "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    inventory-operation) inventory_operation "${2:-}" ;;
    inventory-operations) inventory_operations "${2:-}" "${3:-50}" "${4:-0}" ;;
    inventory-item) inventory_item "${2:-}" ;;
    secrets-init) secrets_init ;;
    secrets-setup) secrets_setup "${2:-}" ;;
    secrets-update-web) secrets_update_web "${2:-}" ;;
    secrets-setup-web) secrets_setup_web "${2:-}" ;;
    secrets-ensure-auth) secrets_ensure_auth "${2:-}" ;;
    jwt-rotate) jwt_rotate "${2:-$(active_profile)}" ;;
    mfa-rotate) mfa_rotate "${2:-$(active_profile)}" ;;
    secrets-validate) secrets_validate "${2:-}" ;;
    secrets-status) secrets_status ;;
    secrets-edit) secrets_edit "${2:-}" ;;
    broker-up) broker_up ;;
    broker-down) broker_down ;;
    broker-status) broker_status ;;
    broker-logs) broker_logs ;;
    broker-cli) shift; broker_cli "$@" ;;
    stream-info) stream_info ;;
    stream-read) stream_read "${2:-20}" ;;
    broker-reset-group) broker_reset_group "${2:-$NOTIFICATION_CONSUMER_GROUP}" ;;
    mailpit-up) mailpit_up ;;
    mailpit-down) mailpit_down ;;
    mailpit-status) mailpit_status ;;
    mailpit-logs) mailpit_logs ;;
    mailpit-smoke) mailpit_smoke ;;
    mailpit-send) mailpit_send ;;
    mailpit-list) mailpit_list "${2:-20}" ;;
    mailpit-search) shift; mailpit_search "$*" ;;
    mailpit-clear) mailpit_clear ;;
    profile-show) profile_show ;;
    profile-set) profile_set "${2:-}" ;;
    language-show) language_show ;;
    language-set) language_set "${2:-}" ;;
    prod-status) prod_status ;;
    db-list) print_database_catalog ;;
    db-up) db_action up "${2:-all}" ;;
    db-down) db_action down "${2:-all}" ;;
    db-status) db_action status "${2:-all}" ;;
    db-logs) db_action logs "${2:-all}" ;;
    db-shell) db_shell "${2:-}" ;;
    db-down-all) db_down_all ;;
    db-backup) db_backup "${2:-all}" ;;
    db-backup-all) db_backup all ;;
    privacy-journal-sync) privacy_journal_sync ;;
    privacy-restore-drill) privacy_restore_drill "${2:-latest}" "${3:-}" ;;
    platform-backup) platform_backup ;;
    platform-restore-drill) platform_restore_drill "${2:-latest}" "${3:-}" ;;
    platform-backup-prune) platform_backup_prune "${2:-$BACKUP_RETENTION_DAYS}" ;;
    db-backup-list) db_backup_list "${2:-all}" ;;
    db-backup-prune) db_backup_prune "${2:-$BACKUP_RETENTION_DAYS}" ;;
    db-restore-test) db_restore_test "${2:-all}" ;;
    backup-key-init) backup_key_init "${2:-all}" ;;
    backup-schedule-install) backup_schedule_install "${2:-2}" ;;
    backup-schedule-remove) backup_schedule_remove ;;
    backup-schedule-status) backup_schedule_status ;;
    inventory-reset) inventory_reset ;;
    demo-reset) demo_reset ;;
    app-run) run_app "${2:-}" "${3:-}" ;;
    app-stop) app_stop "${2:-}" ;;
    app-run-all) app_run_all ;;
    app-stop-all) app_stop_all ;;
    app-status-all) app_status_all ;;
    app-logs) app_logs "${2:-}" ;;
    app-test) run_tests "${2:-all}" ;;
    app-package) run_package "${2:-all}" ;;
    health) health "${2:-all}" ;;
    smoke) smoke ;;
    delivery-smoke) delivery_smoke ;;
    delivery-tracking) delivery_tracking "${2:-}" ;;
    delivery-tracking-status) delivery_tracking_status "${2:-}" "${3:-}" ;;
    returns-list) returns_list "${2:-50}" "${3:-0}" ;;
    returns-status) returns_status "${2:-}" "${3:-}" "${4:-}" "${5:-}" "${6:-}" ;;
    returns-show) returns_show "${2:-}" ;;
    returns-history) returns_history "${2:-}" "${3:-50}" "${4:-0}" ;;
    returns-reconcile) returns_reconcile "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
    promotion-smoke) promotion_smoke ;;
    promotion-codes) promotion_codes "${2:-}" ;;
    promotion-code-create) promotion_code_create ;;
    orders-list) orders_list "${2:-100}" "${3:-0}" ;;
    invoices-list) invoices_list "${2:-50}" "${3:-0}" ;;
    invoice-show) billing_invoice "${2:-}" ;;
    invoice-pdf) invoice_pdf "${2:-}" ;;
    invoices-export-zip) invoices_export_zip "${2:-}" "${3:-}" "${4:-}" ;;
    invoice-balance) invoice_balance "${2:-}" ;;
    invoice-correct) invoice_correct "${2:-}" "${3:-}" "${4:-}" "${5:-}" "${6:-}" ;;
    corrections-list) corrections_list "${2:-}" ;;
    review-pending) review_pending "${2:-50}" "${3:-0}" ;;
    review-moderate) shift; review_moderate "$@" ;;
    chat-list) chat_messages "${2:-50}" "${3:-0}" ;;
    chat-reply) shift; chat_reply "$@" ;;
    chat-history) chat_history "${2:-}" "${3:-0}" ;;
    chat-notification-retry) chat_notification_retry "${2:-}" "${3:-}" ;;
    swagger-ui) swagger_ui ;;
    api-docs-urls) api_docs_urls ;;
    contract-test) qa_contract_test ;;
    security-test) qa_security_test ;;
    zap-dast) qa_zap_dast ;;
    load-test) qa_load_test ;;
    resilience-test) shift; qa_resilience_test "$@" ;;
    e2e) qa_e2e ;;
    ui-test-status) qa_ui_status ;;
    ui-test-install) qa_ui_install ;;
    ui-test-smoke) qa_ui_smoke ;;
    ui-test) qa_ui_regression ;;
    ui-test-system) qa_ui_system ;;
    ui-test-browsers) qa_ui_browsers ;;
    ui-test-headed) qa_ui_headed ;;
    ui-test-debug) qa_ui_debug ;;
    ui-test-report) qa_ui_report ;;
    images-build) images_build ;;
    docker-apps-up) docker_apps_up ;;
    docker-apps-down) docker_apps_down ;;
    docker-apps-status) docker_apps_status ;;
    docker-apps-logs) shift; docker_apps_logs "$@" ;;
    apps-compose) shift; apps_compose "$@" ;;
    k8s-validate) k8s_validate ;;
    k8s-status) k8s_status ;;
    openbao-status) openbao_status ;;
    openbao-validate) openbao_validate ;;
    tls-status) tls_status ;;
    resilience-status) resilience_status ;;
    jwt-status) jwt_status ;;
    jwt-smoke) jwt_smoke "${2:-}" ;;
    audit-list) audit_list "${2:-50}" ;;
    password-reset-request) password_reset_request "${2:-}" ;;
    app-verify) app_verify ;;
    verify-mvp) shift; verify_mvp auto "$@" ;;
    doctor) doctor ;;
    doctor-fix) doctor_auto_fix ;;
    fresh-start) workflow_fresh_start ;;
    seed-demo-data) workflow_seed_demo_data ;;
    frontend-up) frontend_up ;;
    frontend-down) frontend_down ;;
    frontend-status) frontend_status ;;
    frontend-logs) frontend_logs ;;
    web-start|web|web-ui) shift; web_start "$@" ;;
    "") main_menu ;;
    *) printf '%sNieznana komenda: %s%s\n\n' "$C_RED" "$1" "$C_RESET" >&2; print_catalog >&2; return 2 ;;
  esac
}


load_language
command_dispatch "$@"

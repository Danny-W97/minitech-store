#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd)"
source "$PROJECT_ROOT/scripts/control-center/lib/platform-auth.sh"
for project in product-service order-service customer-service api-gateway; do
  (
    export PLATFORM_PRODUCT_SERVICE_TOKEN=product-credential
    export PLATFORM_ORDER_SERVICE_TOKEN=order-credential
    export PLATFORM_CUSTOMER_SERVICE_TOKEN=customer-credential
    export PLATFORM_SERVICE_CREDENTIALS_JSON=private-registry
    export PLATFORM_OPERATOR_TOKEN=operator-credential
    export QA_OPERATOR_TOKEN=qa-operator-credential
    export CUSTOMER_JWT_SECRET=private-signing-key
    export CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=private-mfa-key
    export CUSTOMER_MFA_LEGACY_KEY_BASE64=private-old-mfa-key
    export CUSTOMER_MFA_PREVIOUS_KEYS_JSON=private-mfa-key-ring
    configure_service_auth "$project"
    [[ -z "${QA_OPERATOR_TOKEN:-}" ]]
    [[ -z "${PLATFORM_PRODUCT_SERVICE_TOKEN:-}" && -z "${PLATFORM_ORDER_SERVICE_TOKEN:-}" && -z "${PLATFORM_CUSTOMER_SERVICE_TOKEN:-}" ]]
    if [[ "$project" == customer-service ]]; then
      [[ "$PLATFORM_SERVICE_CREDENTIALS_JSON" == private-registry && "$PLATFORM_OPERATOR_TOKEN" == operator-credential ]]
      [[ "$CUSTOMER_JWT_SECRET" == private-signing-key ]]
      [[ "$CUSTOMER_MFA_ENCRYPTION_KEY_BASE64" == private-mfa-key ]]
    else
      [[ -z "${PLATFORM_SERVICE_CREDENTIALS_JSON:-}" && -z "${PLATFORM_OPERATOR_TOKEN:-}" && -z "${CUSTOMER_JWT_SECRET:-}" ]]
      [[ -z "${CUSTOMER_MFA_ENCRYPTION_KEY_BASE64:-}" && -z "${CUSTOMER_MFA_LEGACY_KEY_BASE64:-}" && -z "${CUSTOMER_MFA_PREVIOUS_KEYS_JSON:-}" ]]
    fi
    if [[ "$project" == api-gateway ]]; then [[ -z "${PLATFORM_SERVICE_TOKEN:-}" ]];
    else [[ -n "$PLATFORM_SERVICE_TOKEN" ]]; fi
  )
done
printf 'PASS: service launchers isolate service, operator and JWT signing credentials\n'

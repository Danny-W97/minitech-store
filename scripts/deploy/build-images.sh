#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
REGISTRY_PREFIX="${IMAGE_PREFIX:-mini-order-platform}"
TAG="${IMAGE_TAG:-local}"

command -v docker >/dev/null 2>&1 || {
  printf 'Brak komendy docker.\n' >&2
  exit 1
}

services=(
  api-gateway
  product-service
  order-service
  inventory-service
  payment-service
  notification-service
  customer-service
  delivery-service
  promotion-service
  wishlist-service
  review-service
  recommendation-service
  chat-service
  returns-service
  billing-service
)

for service in "${services[@]}"; do
  printf 'Buduję obraz %s:%s\n' "$REGISTRY_PREFIX/$service" "$TAG"
  docker build \
    --file "$PROJECT_ROOT/deploy/docker/service.Dockerfile" \
    --build-arg "SERVICE_MODULE=$service" \
    --tag "$REGISTRY_PREFIX/$service:$TAG" \
    "$PROJECT_ROOT"
done

printf 'Buduję obraz frontend:%s\n' "$TAG"
docker build \
  --file "$PROJECT_ROOT/deploy/docker/frontend.Dockerfile" \
  --tag "$REGISTRY_PREFIX/frontend:$TAG" \
  "$PROJECT_ROOT"

printf 'Obrazy zbudowane dla tagu %s.\n' "$TAG"

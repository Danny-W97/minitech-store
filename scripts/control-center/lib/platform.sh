#!/usr/bin/env bash
# Platform detection and integration adapter for Mini Order Platform Control Center

LIB_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_DIR="$(cd -- "$LIB_DIR/.." && pwd)"

# shellcheck source=/dev/null
source "$SCRIPT_DIR/platform.sh"

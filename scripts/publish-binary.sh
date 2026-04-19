#!/usr/bin/env bash
# publish-binary.sh — Publish a binary-mode CloudEvent.
#
# Binary mode puts CloudEvent attributes in ce-* HTTP headers and the data in the body.
# This is the standard encoding used by Knative Eventing.
#
# Usage:
#   ./scripts/publish-binary.sh <type> <source> [data]
#   ./scripts/publish-binary.sh com.example.order.created /orders '{"orderId":"123"}'
#
# Options:
#   -H, --host HOST       Ingress host (default: localhost)
#   -P, --port PORT       Ingress port (default: 8080)
#   -n, --namespace NS    Knative namespace (default: default)
#   -b, --broker NAME     Broker name (default: default)
#   -i, --id ID           Custom event ID (default: auto-generated)
#   -e, --ext KEY=VALUE   CloudEvent extension attribute (repeatable)
#   -c, --content-type CT Data content type (default: application/json)
#   -h, --help            Show help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Binary mode is the default — just forward all args
exec "$SCRIPT_DIR/publish.sh" "$@"

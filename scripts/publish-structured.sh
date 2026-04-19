#!/usr/bin/env bash
# publish-structured.sh — Publish a structured (JSON envelope) CloudEvent.
#
# Structured mode puts the entire CloudEvent (attributes + data) in the HTTP body
# as a JSON document. Content-Type is application/cloudevents+json.
#
# Usage:
#   ./scripts/publish-structured.sh <type> <source> [data]
#   ./scripts/publish-structured.sh com.example.order.created /orders '{"orderId":"123"}'
#
# Options:
#   -H, --host HOST       Ingress host (default: localhost)
#   -P, --port PORT       Ingress port (default: 8080)
#   -n, --namespace NS    Knative namespace (default: default)
#   -b, --broker NAME     Broker name (default: default)
#   -i, --id ID           Custom event ID (default: auto-generated)
#   -e, --ext KEY=VALUE   CloudEvent extension attribute (repeatable)
#   -h, --help            Show help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Forward all args to publish.sh with --structured flag
exec "$SCRIPT_DIR/publish.sh" --structured "$@"

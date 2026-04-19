#!/usr/bin/env bash
# publish.sh — Publish a CloudEvent to the Fluss broker ingress.
#
# Supports both structured and binary CloudEvent encoding.
# Defaults to binary mode (standard Knative encoding).
#
# Usage:
#   ./scripts/publish.sh <type> <source> [data]
#   ./scripts/publish.sh com.example.order.created /orders '{"orderId":"123"}'
#   ./scripts/publish.sh com.example.user.signup /users                    # no data
#
# Options:
#   -H, --host HOST       Ingress host (default: localhost)
#   -P, --port PORT       Ingress port (default: 8080)
#   -n, --namespace NS    Knative namespace (default: default)
#   -b, --broker NAME     Broker name (default: default)
#   -s, --structured      Use structured CloudEvent encoding (default: binary)
#   -i, --id ID           Custom event ID (default: auto-generated UUID)
#   -e, --ext KEY=VALUE   CloudEvent extension attribute (repeatable)
#   -c, --content-type CT Data content type (default: application/json)
#   -h, --help            Show help
#
# Examples:
#   # Binary mode (default)
#   ./scripts/publish.sh com.example.order.created /orders '{"orderId":"123"}'
#
#   # Structured mode
#   ./scripts/publish.sh -s com.example.order.created /orders '{"orderId":"123"}'
#
#   # With extensions
#   ./scripts/publish.sh -e region=us-west-2 -e priority=high \
#     com.example.order.created /orders '{"orderId":"123"}'
#
#   # Custom broker + namespace
#   ./scripts/publish.sh -n my-namespace -b my-broker \
#     com.example.order.created /orders '{"orderId":"123"}'
#
set -euo pipefail

# ── Defaults ─────────────────────────────────────
HOST="localhost"
PORT="8080"
NAMESPACE="default"
BROKER="default"
MODE="binary"
EVENT_ID=""
CONTENT_TYPE="application/json"
EXTENSIONS=()

# ── Colors ───────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[publish]${NC} $*"; }
error() { echo -e "${RED}[publish]${NC} $*" >&2; exit 1; }

# ── Usage ────────────────────────────────────────
usage() {
    sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
    exit 0
}

# ── Parse args ───────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        -H|--host)       HOST="$2"; shift 2 ;;
        -P|--port)       PORT="$2"; shift 2 ;;
        -n|--namespace)  NAMESPACE="$2"; shift 2 ;;
        -b|--broker)     BROKER="$2"; shift 2 ;;
        -s|--structured) MODE="structured"; shift ;;
        -i|--id)         EVENT_ID="$2"; shift 2 ;;
        -e|--ext)        EXTENSIONS+=("$2"); shift 2 ;;
        -c|--content-type) CONTENT_TYPE="$2"; shift 2 ;;
        -h|--help)       usage ;;
        -*)              error "Unknown option: $1" ;;
        *)               break ;;
    esac
done

EVENT_TYPE="${1:-}"
EVENT_SOURCE="${2:-}"
EVENT_DATA="${3:-}"

# ── Validate required args ──────────────────────
if [ -z "$EVENT_TYPE" ]; then
    error "Missing required argument: event type\nUsage: $0 <type> <source> [data]"
fi
if [ -z "$EVENT_SOURCE" ]; then
    error "Missing required argument: event source\nUsage: $0 <type> <source> [data]"
fi

# ── Generate event ID ───────────────────────────
if [ -z "$EVENT_ID" ]; then
    if command -v uuidgen &>/dev/null; then
        EVENT_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
    else
        EVENT_ID="$(date +%s)-$RANDOM"
    fi
fi

# ── Build URL ────────────────────────────────────
URL="http://${HOST}:${PORT}/${NAMESPACE}/${BROKER}"

# ── Build and send request ──────────────────────
if [ "$MODE" = "structured" ]; then
    # Structured CloudEvent: everything in JSON body
    CE_JSON=$(cat <<JSONEOF
{
  "specversion": "1.0",
  "id": "${EVENT_ID}",
  "source": "${EVENT_SOURCE}",
  "type": "${EVENT_TYPE}",
  "datacontenttype": "${CONTENT_TYPE}",
  "time": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
JSONEOF
)

    # Add extensions
    for ext in "${EXTENSIONS[@]+"${EXTENSIONS[@]}"}"; do
        KEY="${ext%%=*}"
        VALUE="${ext#*=}"
        CE_JSON="${CE_JSON},\"${KEY}\":\"${VALUE}\""
    done

    # Add data
    if [ -n "$EVENT_DATA" ]; then
        CE_JSON="${CE_JSON},\"data\":${EVENT_DATA}"
    fi

    CE_JSON="${CE_JSON}}"

    info "Structured CloudEvent → ${URL}"
    info "  type:   ${EVENT_TYPE}"
    info "  source: ${EVENT_SOURCE}"
    info "  id:     ${EVENT_ID}"

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$URL" \
        -H "Content-Type: application/cloudevents+json" \
        -d "$CE_JSON")

else
    # Binary CloudEvent: attributes as ce-* headers, data in body
    HEADERS=(
        -H "ce-specversion: 1.0"
        -H "ce-id: ${EVENT_ID}"
        -H "ce-source: ${EVENT_SOURCE}"
        -H "ce-type: ${EVENT_TYPE}"
        -H "Content-Type: ${CONTENT_TYPE}"
    )

    # Add extensions as ce-* headers
    for ext in "${EXTENSIONS[@]+"${EXTENSIONS[@]}"}"; do
        KEY="${ext%%=*}"
        VALUE="${ext#*=}"
        HEADERS+=(-H "ce-${KEY}: ${VALUE}")
    done

    info "Binary CloudEvent → ${URL}"
    info "  type:   ${EVENT_TYPE}"
    info "  source: ${EVENT_SOURCE}"
    info "  id:     ${EVENT_ID}"

    if [ -n "$EVENT_DATA" ]; then
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$URL" \
            "${HEADERS[@]}" \
            -d "$EVENT_DATA")
    else
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "$URL" \
            "${HEADERS[@]}" \
            -d "")
    fi
fi

# ── Check response ──────────────────────────────
if [ "$HTTP_CODE" = "202" ]; then
    info "${GREEN}✓ Accepted (202)${NC}"
elif [ "$HTTP_CODE" = "200" ]; then
    info "${GREEN}✓ OK (200)${NC}"
elif [ "$HTTP_CODE" = "000" ]; then
    error "Connection refused — is the ingress running at ${HOST}:${PORT}?"
else
    error "Unexpected HTTP ${HTTP_CODE} from ${URL}"
fi

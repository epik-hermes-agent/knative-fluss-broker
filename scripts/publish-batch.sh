#!/usr/bin/env bash
# publish-batch.sh — Publish a batch of CloudEvents from a JSON file or stdin.
#
# Reads a JSON array of CloudEvent objects and publishes each one.
# Each object must have: type, source, data (optional), extensions (optional).
#
# Usage:
#   ./scripts/publish-batch.sh events.json
#   echo '[...]' | ./scripts/publish-batch.sh -
#   ./scripts/publish-batch.sh -s events.json              # structured mode
#
# Input format (JSON array):
#   [
#     {
#       "type": "com.example.order.created",
#       "source": "/orders",
#       "data": {"orderId": "123"}
#     },
#     {
#       "type": "com.example.user.signup",
#       "source": "/users",
#       "data": {"userId": "abc"},
#       "extensions": {"region": "us-west-2"}
#     }
#   ]
#
# Options:
#   -H, --host HOST       Ingress host (default: localhost)
#   -P, --port PORT       Ingress port (default: 8080)
#   -n, --namespace NS    Knative namespace (default: default)
#   -b, --broker NAME     Broker name (default: default)
#   -s, --structured      Use structured CloudEvent encoding
#   -d, --delay MS        Delay between events in ms (default: 0)
#   -h, --help            Show help
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

HOST="localhost"
PORT="8080"
NAMESPACE="default"
BROKER="default"
MODE="binary"
DELAY_MS="0"
INPUT_FILE=""

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${GREEN}[batch]${NC} $*"; }
error() { echo -e "${RED}[batch]${NC} $*" >&2; exit 1; }

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
        -d|--delay)      DELAY_MS="$2"; shift 2 ;;
        -h|--help)       usage ;;
        -*)              error "Unknown option: $1" ;;
        *)               INPUT_FILE="$1"; shift ;;
    esac
done

if [ -z "$INPUT_FILE" ]; then
    error "Missing input file. Use - for stdin.\nUsage: $0 [options] <file.json | ->"
fi

# ── Read input ───────────────────────────────────
if [ "$INPUT_FILE" = "-" ]; then
    INPUT=$(cat)
else
    if [ ! -f "$INPUT_FILE" ]; then
        error "File not found: $INPUT_FILE"
    fi
    INPUT=$(cat "$INPUT_FILE")
fi

# ── Parse and publish ───────────────────────────
EVENT_COUNT=$(echo "$INPUT" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null)
if [ -z "$EVENT_COUNT" ] || [ "$EVENT_COUNT" -eq 0 ] 2>/dev/null; then
    error "Input must be a non-empty JSON array of CloudEvent objects"
fi

info "Publishing ${EVENT_COUNT} events to ${HOST}:${PORT}/${NAMESPACE}/${BROKER}"
echo ""

PUBLISH_ARGS=(-H "$HOST" -P "$PORT" -n "$NAMESPACE" -b "$BROKER")
if [ "$MODE" = "structured" ]; then
    PUBLISH_ARGS+=(-s)
fi

SUCCESS=0
FAILED=0

for i in $(seq 0 $((EVENT_COUNT - 1))); do
    EVENT_TYPE=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin)[$i]['type'])")
    EVENT_SOURCE=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin)[$i]['source'])")
    EVENT_DATA=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin)[$i].get('data'); print(json.dumps(d) if d else '')" 2>/dev/null || echo "")

    # Build extension args
    EXT_ARGS=()
    EXTENSIONS=$(echo "$INPUT" | python3 -c "
import sys, json
ext = json.load(sys.stdin)[$i].get('extensions', {})
for k, v in ext.items():
    print(f'{k}={v}')
" 2>/dev/null || echo "")

    while IFS= read -r ext; do
        if [ -n "$ext" ]; then
            EXT_ARGS+=(-e "$ext")
        fi
    done <<< "$EXTENSIONS"

    printf "${CYAN}[%d/%d]${NC} %s" $((i + 1)) "$EVENT_COUNT" "$EVENT_TYPE"

    if "$SCRIPT_DIR/publish.sh" "${PUBLISH_ARGS[@]}" "${EXT_ARGS[@]}" \
        "$EVENT_TYPE" "$EVENT_SOURCE" "$EVENT_DATA" >/dev/null 2>&1; then
        printf " ${GREEN}✓${NC}\n"
        SUCCESS=$((SUCCESS + 1))
    else
        printf " ${RED}✗${NC}\n"
        FAILED=$((FAILED + 1))
    fi

    # Delay between events
    if [ "$DELAY_MS" -gt 0 ] 2>/dev/null; then
        sleep "$(echo "scale=3; $DELAY_MS / 1000" | bc 2>/dev/null || echo "0")"
    fi
done

echo ""
info "Done: ${GREEN}${SUCCESS} succeeded${NC}, ${RED}${FAILED} failed${NC}"

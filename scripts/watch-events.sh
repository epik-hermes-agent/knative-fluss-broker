#!/usr/bin/env bash
# watch-events.sh — Watch and display events flowing through the broker.
#
# Connects to Fluss via the Flink SQL client or Fluss CLI to show events
# in the broker's log table. Works with docker-compose Fluss cluster.
#
# Usage:
#   ./scripts/watch-events.sh                              # default broker
#   ./scripts/watch-events.sh -n my-namespace -b my-broker  # custom broker
#   ./scripts/watch-events.sh --tail 50                     # show last 50 events
#   ./scripts/watch-events.sh --follow                      # continuous polling
#
# Options:
#   -n, --namespace NS    Knative namespace (default: default)
#   -b, --broker NAME     Broker name (default: default)
#   -t, --tail N          Show last N events (default: 20)
#   -f, --follow          Poll every N seconds (default: 5)
#   -p, --poll SEC        Poll interval in seconds (default: 5)
#   -H, --host HOST       Fluss host (default: localhost)
#   -P, --port PORT       Fluss port (default: 9123)
#   -h, --help            Show help
#
set -euo pipefail

NAMESPACE="default"
BROKER="default"
TAIL=20
FOLLOW=false
POLL_SEC=5
FLUSS_HOST="localhost"
FLUSS_PORT="9123"

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[watch]${NC} $*"; }
error() { echo -e "${RED}[watch]${NC} $*" >&2; exit 1; }

usage() {
    sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--namespace)  NAMESPACE="$2"; shift 2 ;;
        -b|--broker)     BROKER="$2"; shift 2 ;;
        -t|--tail)       TAIL="$2"; shift 2 ;;
        -f|--follow)     FOLLOW=true; shift ;;
        -p|--poll)       POLL_SEC="$2"; shift 2 ;;
        -H|--host)       FLUSS_HOST="$2"; shift 2 ;;
        -P|--port)       FLUSS_PORT="$2"; shift 2 ;;
        -h|--help)       usage ;;
        *)               error "Unknown option: $1" ;;
    esac
done

# ── Resolve table path ──────────────────────────
SANITIZE_NS=$(echo "$NAMESPACE" | sed 's/[^a-zA-Z0-9_]/_/g' | tr '[:upper:]' '[:lower:]')
SANITIZE_BROKER=$(echo "$BROKER" | sed 's/[^a-zA-Z0-9_]/_/g' | tr '[:upper:]' '[:lower:]')
DATABASE="knative_${SANITIZE_NS}"
TABLE="broker_${SANITIZE_BROKER}"
FULL_PATH="${DATABASE}.${TABLE}"

info "Watching events in ${FULL_PATH}"
info "Fluss: ${FLUSS_HOST}:${FLUSS_PORT}"
echo ""

# ── Check Fluss connectivity ────────────────────
if ! nc -zv "$FLUSS_HOST" "$FLUSS_PORT" &>/dev/null 2>&1; then
    error "Cannot reach Fluss at ${FLUSS_HOST}:${FLUSS_PORT}. Is docker-compose running?"
fi

# ── Query function ──────────────────────────────
query_events() {
    # Use a simple HTTP-based approach or CLI
    # For local dev, we use docker exec to run Fluss SQL
    local sql="SELECT event_id, event_type, event_source, CAST(event_time AS STRING) as event_time, content_type, CAST(ingestion_time AS STRING) as ingestion_time FROM ${FULL_PATH} ORDER BY ingestion_time DESC LIMIT ${TAIL};"

    # Try docker exec first (most reliable for local dev)
    if docker exec fluss-coordinator /opt/fluss/bin/sql-client.sh \
        --init /dev/stdin <<< "SET 'sql-client.execution.result-mode' = 'tableau'; ${sql}" 2>/dev/null; then
        return 0
    fi

    # Fallback: show what we can
    echo -e "${YELLOW}Fluss SQL client not available via docker exec.${NC}"
    echo ""
    echo "To query events manually, run:"
    echo "  docker exec -it fluss-coordinator /opt/fluss/bin/sql-client.sh"
    echo "  > SELECT * FROM ${FULL_PATH} ORDER BY ingestion_time DESC LIMIT ${TAIL};"
    echo ""
    echo "Or use Flink SQL (lakehouse profile):"
    echo "  docker exec -it fluss-flink-jobmanager /opt/flink/bin/sql-client.sh"
    echo "  > SELECT * FROM ${FULL_PATH} ORDER BY ingestion_time DESC LIMIT ${TAIL};"
    return 1
}

# ── Main loop ────────────────────────────────────
if [ "$FOLLOW" = true ]; then
    info "Polling every ${POLL_SEC}s (Ctrl+C to stop)..."
    while true; do
        clear
        echo -e "${CYAN}═══ ${FULL_PATH} ═══ $(date '+%H:%M:%S') ═══${NC}"
        echo ""
        query_events || true
        sleep "$POLL_SEC"
    done
else
    echo -e "${CYAN}═══ ${FULL_PATH} ═══${NC}"
    echo ""
    query_events || true
fi

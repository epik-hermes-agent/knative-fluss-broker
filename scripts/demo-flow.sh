#!/usr/bin/env bash
# demo-flow.sh — Live demo: start ingress, publish burst of events, watch dashboard.
#
# Usage:
#   ./scripts/demo-flow.sh              # One-shot: 50 events, mixed types
#   ./scripts/demo-flow.sh --continuous  # Continuous: 5 events/sec until Ctrl-C
#   ./scripts/demo-flow.sh --count 200   # Custom burst size
#   ./scripts/demo-flow.sh --rate 10     # Continuous at 10 events/sec
#
# What it does:
#   1. Checks Fluss cluster is up
#   2. Starts IngressServer on :8080 (if not running)
#   3. Publishes realistic CloudEvents in binary mode
#   4. Dashboard at http://localhost:9090 shows live particles
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# ── Defaults ──────────────────────────────────────────
MODE="burst"
COUNT=50
RATE=5
HOST="localhost"
PORT="8080"
INGRESS_PID=""

# ── Colors ────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${GREEN}[demo]${NC} $*"; }
warn()  { echo -e "${YELLOW}[demo]${NC} $*"; }
error() { echo -e "${RED}[demo]${NC} $*" >&2; exit 1; }

# ── Cleanup ───────────────────────────────────────────
cleanup() {
    if [ -n "$INGRESS_PID" ]; then
        info "Stopping ingress server (PID $INGRESS_PID)..."
        kill "$INGRESS_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ── Parse args ────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --continuous) MODE="continuous"; shift ;;
        --count)      COUNT="$2"; shift 2 ;;
        --rate)       RATE="$2"; shift 2 ;;
        -H|--host)    HOST="$2"; shift 2 ;;
        -P|--port)    PORT="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
            exit 0 ;;
        *) error "Unknown option: $1" ;;
    esac
done

# ── Check Fluss ───────────────────────────────────────
info "Checking Fluss cluster..."
if ! nc -z localhost 9123 2>/dev/null; then
    error "Fluss not reachable on localhost:9123. Run: make docker-up"
fi
info "  Fluss coordinator: ✓"

if ! nc -z localhost 9125 2>/dev/null; then
    warn "Fluss internal port 9125 not reachable (OK for host clients)"
fi

# ── Start Ingress ─────────────────────────────────────
start_ingress() {
    if nc -z "$HOST" "$PORT" 2>/dev/null; then
        info "Ingress already running on ${HOST}:${PORT}"
        return
    fi

    info "Starting IngressServer on ${HOST}:${PORT}..."
    JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}" \
    FLUSS_HOST=localhost \
    FLUSS_PORT=9123 \
    SERVER_PORT="$PORT" \
        "$PROJECT_DIR/gradlew" :data-plane-ingress:run --no-daemon -q &
    INGRESS_PID=$!

    # Wait for health
    for i in $(seq 1 30); do
        if curl -sf "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
            info "Ingress server: ✓ (PID $INGRESS_PID)"
            return
        fi
        sleep 0.5
    done
    error "Ingress server failed to start after 15s"
}

# ── Event Templates ───────────────────────────────────
ORDER_CURRENCIES=("USD" "EUR" "GBP" "JPY")
ORDER_AMOUNTS=("9.99" "29.99" "49.99" "99.99" "149.50" "249.00" "499.99" "1299.00")
CUSTOMERS=("cust-01" "cust-07" "cust-12" "cust-23" "cust-42" "cust-88" "cust-99")
REGIONS=("us-east-1" "us-west-2" "eu-west-1" "ap-southeast-1")
STATUSES=("success" "success" "success" "success" "pending" "failed")
PLANS=("free" "pro" "enterprise")

pick() { echo "${@:1:1 + RANDOM % $#}"; }

random_order() {
    local ord_id="ord-$(printf '%05d' $((RANDOM % 99999)))"
    local cust_id=$(pick "${CUSTOMERS[@]}")
    local currency=$(pick "${ORDER_CURRENCIES[@]}")
    local amount=$(pick "${ORDER_AMOUNTS[@]}")
    local region=$(pick "${REGIONS[@]}")
    echo "{\"orderId\":\"${ord_id}\",\"amount\":${amount},\"currency\":\"${currency}\",\"customerId\":\"${cust_id}\"}" 
}

random_payment() {
    local pay_id="pay-$(printf '%05d' $((RANDOM % 99999)))"
    local ord_id="ord-$(printf '%05d' $((RANDOM % 99999)))"
    local status=$(pick "${STATUSES[@]}")
    local method=$(pick "card" "paypal" "bank" "crypto")
    echo "{\"paymentId\":\"${pay_id}\",\"orderId\":\"${ord_id}\",\"status\":\"${status}\",\"method\":\"${method}\"}"
}

random_user() {
    local user_id="user-$(printf '%04d' $((RANDOM % 9999)))"
    local names=("alice" "bob" "carol" "dave" "eve" "frank" "grace")
    local name=$(pick "${names[@]}")
    local plan=$(pick "${PLANS[@]}")
    echo "{\"userId\":\"${user_id}\",\"email\":\"${name}@example.com\",\"plan\":\"${plan}\"}"
}

random_inventory() {
    local skus=("WIDGET-A" "GADGET-B" "GIZMO-C" "DOOHICKEY-D" "THINGAMAJIG-E")
    local sku=$(pick "${skus[@]}")
    local qty=$((RANDOM % 500 + 1))
    local warehouse=$(pick "wh-east" "wh-west" "wh-eu")
    echo "{\"sku\":\"${sku}\",\"quantity\":${qty},\"warehouse\":\"${warehouse}\",\"action\":\"$(pick reserve release adjust)\"}"
}

# ── Publish One Event ─────────────────────────────────
publish_event() {
    local type="$1" source="$2" data="$3" ext="${4:-}"
    local event_id="demo-$(date +%s%N | cut -c1-13)-$RANDOM"

    local HEADERS=(
        -H "ce-specversion: 1.0"
        -H "ce-id: ${event_id}"
        -H "ce-source: ${source}"
        -H "ce-type: ${type}"
        -H "Content-Type: application/json"
    )

    # Add extensions
    if [ -n "$ext" ]; then
        IFS=',' read -ra EXTS <<< "$ext"
        for e in "${EXTS[@]}"; do
            HEADERS+=(-H "ce-${e}")
        done
    fi

    curl -sf -o /dev/null \
        -X POST "http://${HOST}:${PORT}/demo/default" \
        "${HEADERS[@]}" \
        -d "$data" || warn "Failed to publish ${type}"
}

# ── Publish One Random Event ──────────────────────────
publish_random() {
    local roll=$((RANDOM % 100))
    local region=$(pick "${REGIONS[@]}")

    if [ $roll -lt 40 ]; then
        # 40% orders
        publish_event "com.example.order.created" "/orders" "$(random_order)" "region=${region},priority=$(pick high medium low)"
    elif [ $roll -lt 65 ]; then
        # 25% payments
        publish_event "com.example.payment.processed" "/payments" "$(random_payment)"
    elif [ $roll -lt 80 ]; then
        # 15% user signups
        publish_event "com.example.user.signup" "/users" "$(random_user)" "plan=$(pick free pro enterprise)"
    else
        # 20% inventory
        publish_event "com.example.inventory.updated" "/inventory" "$(random_inventory)" "warehouse=$(pick wh-east wh-west wh-eu)"
    fi
}

# ── Main ──────────────────────────────────────────────
echo ""
echo -e "${BOLD}${CYAN}  ╔══════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}${CYAN}  ║  Knative Fluss Broker — Live Demo Flow       ║${NC}"
echo -e "${BOLD}${CYAN}  ╚══════════════════════════════════════════════╝${NC}"
echo ""

start_ingress

echo ""
info "Event distribution:"
info "  40% orders    (com.example.order.created)"
info "  25% payments  (com.example.payment.processed)"
info "  15% users     (com.example.user.signup)"
info "  20% inventory (com.example.inventory.updated)"
echo ""

if [ "$MODE" = "burst" ]; then
    info "Publishing ${COUNT} events to http://${HOST}:${PORT}/demo/default"
    echo ""

    for i in $(seq 1 "$COUNT"); do
        publish_random
        printf "\r${GREEN}[demo]${NC} Published: ${BOLD}%d${NC}/%d" "$i" "$COUNT"
        sleep 0.05
    done
    echo ""
    echo ""
    info "${GREEN}✓ Done! ${COUNT} events published.${NC}"
    info "Watch the dashboard: ${CYAN}http://localhost:9090/dashboard/data-flow-dashboard.html${NC}"

elif [ "$MODE" = "continuous" ]; then
    info "Publishing continuously at ${RATE} events/sec (Ctrl-C to stop)"
    info "Watch the dashboard: ${CYAN}http://localhost:9090/dashboard/data-flow-dashboard.html${NC}"
    echo ""

    interval=$(echo "scale=3; 1 / $RATE" | bc)
    count=0
    while true; do
        publish_random
        count=$((count + 1))
        printf "\r${GREEN}[demo]${NC} Published: ${BOLD}%d${NC} events" "$count"
        sleep "$interval"
    done
fi

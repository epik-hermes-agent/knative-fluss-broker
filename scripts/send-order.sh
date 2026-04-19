#!/usr/bin/env bash
# send-order.sh — Quick example: send a sample order CloudEvent.
#
# A convenience wrapper for the most common use case — sending an order event.
#
# Usage:
#   ./scripts/send-order.sh                          # default order
#   ./scripts/send-order.sh 456                      # custom order ID
#   ./scripts/send-order.sh 456 '{"item":"widget"}'  # with custom data
#   ./scripts/send-order.sh -n my-ns -b my-broker    # custom namespace/broker
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ORDER_ID="${1:-$(date +%s)}"
CUSTOM_DATA="${2:-}"

# Parse forwarded options (everything before the positional args)
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -*) EXTRA_ARGS+=("$1" "$2"); shift 2 ;;  # pass through options
        *)  shift ;;
    esac
done

if [ -n "$CUSTOM_DATA" ]; then
    DATA="$CUSTOM_DATA"
else
    DATA="{\"orderId\":\"${ORDER_ID}\",\"amount\":$(shuf -i 10-500 -n 1),\"currency\":\"USD\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
fi

exec "$SCRIPT_DIR/publish.sh" "${EXTRA_ARGS[@]}" \
    com.example.order.created \
    "/orders/${ORDER_ID}" \
    "$DATA"

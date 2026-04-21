#!/usr/bin/env bash
# launch-tui.sh — Launch the live TUI dashboard.
#
# Usage:
#   ./scripts/launch-tui.sh                            # default config
#   ./scripts/launch-tui.sh --gateway localhost:8083    # custom gateway
#   ./scripts/launch-tui.sh --fluss localhost:9123      # custom fluss
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_DIR/tools/tui/build/libs/tui-0.1.0-SNAPSHOT-all.jar"

# Build if needed
if [ ! -f "$JAR" ]; then
    echo "Building TUI dashboard..."
    "$PROJECT_DIR/gradlew" :tools:tui:shadowJar --no-daemon -q
fi

exec java -jar "$JAR" "$@"

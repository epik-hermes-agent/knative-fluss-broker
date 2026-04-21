#!/usr/bin/env bash
# cleanup.sh — Full teardown and cleanup of the Fluss + Flink + LocalStack + Polaris environment.
#
# Kills all running jobs, tears down Docker containers and volumes,
# wipes S3 buckets in LocalStack, and clears Iceberg metadata.
#
# Usage:
#   ./scripts/cleanup.sh            # full cleanup (containers + volumes + data)
#   ./scripts/cleanup.sh --jobs     # cancel Flink jobs only (keep containers)
#   ./scripts/cleanup.sh --soft     # cancel jobs + wipe data, keep containers running
#   ./scripts/cleanup.sh --lakehouse  # also tear down lakehouse profile containers
#
set -euo pipefail

# ── Config ──────────────────────────────────────────
FLINK_URL="${FLINK_URL:-http://localhost:8081}"
LOCALSTACK_URL="${LOCALSTACK_URL:-http://localhost:4566}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$PROJECT_DIR/docker/docker-compose.yml"

# ── Colors ──────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[cleanup]${NC} $*"; }
warn()  { echo -e "${YELLOW}[cleanup]${NC} $*"; }
error() { echo -e "${RED}[cleanup]${NC} $*"; }
step()  { echo -e "\n${CYAN}════ $* ════${NC}"; }

# ── Parse args ──────────────────────────────────────
MODE="full"
LAKEHOUSE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --jobs)       MODE="jobs"; shift ;;
        --soft)       MODE="soft"; shift ;;
        --lakehouse)  LAKEHOUSE=true; shift ;;
        -h|--help)
            sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
            exit 0 ;;
        *) error "Unknown option: $1"; exit 1 ;;
    esac
done

# ══════════════════════════════════════════════════
# Step 1: Cancel all Flink jobs
# ══════════════════════════════════════════════════
step "Cancelling Flink jobs"

if curl -sf "$FLINK_URL/jobs/overview" > /dev/null 2>&1; then
    JOB_COUNT=$(curl -sf "$FLINK_URL/jobs/overview" | python3 -c "
import json,sys
d=json.load(sys.stdin)
jobs = d.get('jobs',[])
running = [j for j in jobs if j.get('state') == 'RUNNING']
print(len(running))
" 2>/dev/null || echo "0")

    if [ "$JOB_COUNT" -gt 0 ]; then
        info "Found $JOB_COUNT running job(s), cancelling..."
        curl -sf "$FLINK_URL/jobs/overview" | python3 -c "
import json,sys,urllib.request
d=json.load(sys.stdin)
for j in d.get('jobs',[]):
    if j.get('state','') == 'RUNNING':
        jid = j['jid']
        name = j.get('name','?')[:40]
        try:
            req = urllib.request.Request(f'http://localhost:8081/jobs/{jid}?mode=cancel',
                                         data=b'', method='PATCH',
                                         headers={'Content-Type':'application/json'})
            urllib.request.urlopen(req, timeout=10)
            print(f'  ✓ Cancelled: {name}')
        except Exception as e:
            print(f'  ⚠ Failed: {name} -> {e}')
" 2>/dev/null || warn "Could not cancel jobs (Flink may be down)"
        info "Waiting 5s for slots to release..."
        sleep 5
    else
        info "No running Flink jobs found"
    fi

    # Also cancel FAILED jobs that might be stuck
    curl -sf "$FLINK_URL/jobs/overview" | python3 -c "
import json,sys,urllib.request
d=json.load(sys.stdin)
for j in d.get('jobs',[]):
    if j.get('state','') == 'FAILED':
        jid = j['jid']
        try:
            req = urllib.request.Request(f'http://localhost:8081/jobs/{jid}?mode=cancel',
                                         data=b'', method='PATCH',
                                         headers={'Content-Type':'application/json'})
            urllib.request.urlopen(req, timeout=10)
        except: pass
" 2>/dev/null || true

    info "Slots after cleanup:"
    curl -sf "$FLINK_URL/taskmanagers" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for tm in d.get('taskmanagers',[]):
    print(f'  TM: {tm.get(\"slotsNumber\",0)} slots, {tm.get(\"freeSlots\",0)} free')
" 2>/dev/null || warn "Could not check slots"
else
    warn "Flink JobManager not reachable at $FLINK_URL"
fi

if [ "$MODE" = "jobs" ]; then
    info "Jobs-only mode — done."
    exit 0
fi

# ══════════════════════════════════════════════════
# Step 2: Wipe S3 buckets in LocalStack
# ══════════════════════════════════════════════════
if [ "$MODE" = "soft" ] || [ "$MODE" = "full" ]; then
    step "Wiping S3 buckets in LocalStack"

    if curl -sf "$LOCALSTACK_URL/_localstack/health" > /dev/null 2>&1; then
        for bucket in fluss-data iceberg-warehouse; do
            info "Emptying s3://$bucket..."
            AWS_ENDPOINT_URL=$LOCALSTACK_URL awslocal s3 rm "s3://$bucket/" --recursive 2>/dev/null || true
            info "Deleting s3://$bucket..."
            AWS_ENDPOINT_URL=$LOCALSTACK_URL awslocal s3 rb "s3://$bucket" 2>/dev/null || true
        done

        # Recreate buckets
        info "Recreating buckets..."
        AWS_ENDPOINT_URL=$LOCALSTACK_URL awslocal s3 mb s3://fluss-data 2>/dev/null || true
        AWS_ENDPOINT_URL=$LOCALSTACK_URL awslocal s3 mb s3://iceberg-warehouse 2>/dev/null || true
        info "S3 cleanup complete"
    else
        warn "LocalStack not reachable at $LOCALSTACK_URL"
    fi
fi

# ══════════════════════════════════════════════════
# Step 3: Soft mode stops here
# ══════════════════════════════════════════════════
if [ "$MODE" = "soft" ]; then
    info "Soft cleanup complete — containers still running"
    exit 0
fi

# ══════════════════════════════════════════════════
# Step 4: Tear down Docker containers + volumes
# ══════════════════════════════════════════════════
step "Tearing down Docker containers"

if [ "$LAKEHOUSE" = true ]; then
    info "Tearing down lakehouse profile..."
    docker compose -f "$COMPOSE_FILE" --profile lakehouse down -v 2>&1 | sed 's/^/  /'
else
    info "Tearing down core services..."
    docker compose -f "$COMPOSE_FILE" down -v 2>&1 | sed 's/^/  /'
fi

info "Removing dangling volumes..."
docker volume ls --filter name=docker_fluss --filter name=docker_flink -q 2>/dev/null | \
    while read -r vol; do
        docker volume rm "$vol" 2>/dev/null && info "  Removed volume: $vol" || true
    done

# ══════════════════════════════════════════════════
# Step 5: Summary
# ══════════════════════════════════════════════════
step "Cleanup complete"

info "Environment is clean. To restart:"
if [ "$LAKEHOUSE" = true ]; then
    info "  docker compose -f docker/docker-compose.yml --profile lakehouse up -d"
else
    info "  docker compose -f docker/docker-compose.yml up -d"
fi
info "  Then wait ~15s and run: make dashboard"

#!/usr/bin/env bash
# health-check.sh — Check health of all broker infrastructure components.
#
# Usage:
#   ./scripts/health-check.sh              # check all core services
#   ./scripts/health-check.sh --lakehouse  # include lakehouse services
#   ./scripts/health-check.sh --json       # output as JSON
#
set -euo pipefail

LAKEHOUSE=false
JSON_OUTPUT=false

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

while [[ $# -gt 0 ]]; do
    case "$1" in
        --lakehouse) LAKEHOUSE=true; shift ;;
        --json)      JSON_OUTPUT=true; shift ;;
        -h|--help)
            sed -n '2,/^$/{ s/^# //; s/^#//; p }' "$0"
            exit 0
            ;;
        *) shift ;;
    esac
done

CHECKS=()

check_service() {
    local name="$1"
    local host="$2"
    local port="$3"
    local extra_check="${4:-}"

    local status="down"
    local detail=""

    if nc -zv "$host" "$port" &>/dev/null 2>&1; then
        status="up"

        # Run extra check if provided
        if [ -n "$extra_check" ]; then
            detail=$(eval "$extra_check" 2>/dev/null || echo "")
        fi
    fi

    CHECKS+=("{\"name\":\"${name}\",\"status\":\"${status}\",\"host\":\"${host}\",\"port\":\"${port}\",\"detail\":\"${detail}\"}")

    if [ "$JSON_OUTPUT" = false ]; then
        if [ "$status" = "up" ]; then
            echo -e "  ${GREEN}✓${NC} ${name} (${host}:${port})"
            [ -n "$detail" ] && echo -e "    ${CYAN}↳${NC} ${detail}"
        else
            echo -e "  ${RED}✗${NC} ${name} (${host}:${port})"
        fi
    fi
}

check_docker_container() {
    local name="$1"
    local container="$2"
    local extra_check="${3:-}"

    local status="down"
    local detail=""

    local health=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "none")
    local state=$(docker inspect --format='{{.State.Status}}' "$container" 2>/dev/null || echo "missing")

    if [ "$state" = "running" ]; then
        status="up"
        if [ "$health" = "healthy" ]; then
            detail="healthy"
        elif [ "$health" = "none" ]; then
            detail="running (no healthcheck)"
        else
            detail="running (${health})"
        fi

        if [ -n "$extra_check" ]; then
            local extra=$(eval "$extra_check" 2>/dev/null || echo "")
            [ -n "$extra" ] && detail="${detail}, ${extra}"
        fi
    else
        detail="$state"
    fi

    CHECKS+=("{\"name\":\"${name}\",\"status\":\"${status}\",\"container\":\"${container}\",\"detail\":\"${detail}\"}")

    if [ "$JSON_OUTPUT" = false ]; then
        if [ "$status" = "up" ]; then
            echo -e "  ${GREEN}✓${NC} ${name} (${container})"
            echo -e "    ${CYAN}↳${NC} ${detail}"
        else
            echo -e "  ${RED}✗${NC} ${name} (${container}) — ${detail}"
        fi
    fi
}

# ── Run checks ──────────────────────────────────
if [ "$JSON_OUTPUT" = false ]; then
    echo -e "${CYAN}═══ Knative Fluss Broker — Health Check ═══${NC}"
    echo ""
    echo -e "${YELLOW}Core Services:${NC}"
fi

check_docker_container "ZooKeeper" "fluss-zookeeper"
check_docker_container "Fluss Coordinator" "fluss-coordinator" \
    "docker logs fluss-coordinator 2>&1 | grep -q 'Started' && echo 'started'"
check_docker_container "Fluss Tablet Server" "fluss-tablet" \
    "docker logs fluss-tablet 2>&1 | grep -q 'Started' && echo 'started'"
check_docker_container "LocalStack (S3)" "fluss-localstack"

if [ "$JSON_OUTPUT" = false ] && [ "$LAKEHOUSE" = true ]; then
    echo ""
    echo -e "${YELLOW}Lakehouse Services:${NC}"
fi

if [ "$LAKEHOUSE" = true ]; then
    check_docker_container "Polaris REST Catalog" "polaris"
    check_docker_container "Flink JobManager" "fluss-flink-jobmanager"
    check_docker_container "Flink TaskManager" "fluss-flink-taskmanager"
    check_docker_container "Flink SQL Gateway" "fluss-flink-sql-gateway"
fi

# ── Port checks ─────────────────────────────────
if [ "$JSON_OUTPUT" = false ]; then
    echo ""
    echo -e "${YELLOW}Port Accessibility (from host):${NC}"
fi

check_service "Fluss (client)" "localhost" "9123"
check_service "LocalStack" "localhost" "4566" \
    "curl -sf http://localhost:4566/_localstack/health | python3 -c 'import sys,json; d=json.load(sys.stdin); print(f\"services: {sum(1 for v in d.get(\"services\",{}).values() if v==\"running\")}\")' 2>/dev/null"

if [ "$LAKEHOUSE" = true ]; then
    check_service "Polaris" "localhost" "8181"
    check_service "Flink UI" "localhost" "8081"
    check_service "SQL Gateway" "localhost" "8083" \
        "curl -sf http://localhost:8083/v1/info | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get(\"version\",\"\"))' 2>/dev/null"
fi

# ── LocalStack bucket check ─────────────────────
if [ "$JSON_OUTPUT" = false ]; then
    echo ""
    echo -e "${YELLOW}S3 Buckets:${NC}"
fi

BUCKETS=$(curl -sf http://localhost:4566/_localstack/health 2>/dev/null | \
    python3 -c "import sys,json; print('s3:running' if json.load(sys.stdin).get('services',{}).get('s3')=='running' else 's3:down')" 2>/dev/null || echo "s3:down")

if echo "$BUCKETS" | grep -q "running"; then
    BUCKET_LIST=$(awslocal --endpoint-url=http://localhost:4566 s3 ls 2>/dev/null | awk '{print $NF}' | tr '\n' ', ' | sed 's/,$//')
    if [ "$JSON_OUTPUT" = false ]; then
        echo -e "  ${GREEN}✓${NC} S3 buckets: ${BUCKET_LIST:-none}"
    fi
else
    if [ "$JSON_OUTPUT" = false ]; then
        echo -e "  ${RED}✗${NC} S3 not available"
    fi
fi

# ── Summary ─────────────────────────────────────
if [ "$JSON_OUTPUT" = true ]; then
    echo "["
    for i in "${!CHECKS[@]}"; do
        echo "  ${CHECKS[$i]}$([ $i -lt $((${#CHECKS[@]} - 1)) ] && echo ",")"
    done
    echo "]"
else
    UP_COUNT=$(printf '%s\n' "${CHECKS[@]}" | grep -c '"status":"up"' || true)
    TOTAL=${#CHECKS[@]}
    echo ""
    echo -e "${CYAN}════════════════════════════════════════${NC}"
    if [ "$UP_COUNT" -eq "$TOTAL" ]; then
        echo -e "${GREEN}All ${TOTAL} services healthy ✓${NC}"
    else
        DOWN=$((TOTAL - UP_COUNT))
        echo -e "${YELLOW}${UP_COUNT}/${TOTAL} services up, ${RED}${DOWN} down${NC}"
    fi
    echo -e "${CYAN}════════════════════════════════════════${NC}"
fi

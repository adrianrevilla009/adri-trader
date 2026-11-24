#!/usr/bin/env bash
# Starts the whole compose stack and checks that the pieces really talk to each other:
# bot healthy -> Prometheus scrapes it -> business metrics exist -> Alertmanager gets the heartbeat -> Grafana has the
# dashboard -> Tempo receives traces. Used by CI; run it locally with `make smoke` (uses a throwaway project name, so
# it never touches your data volumes).
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-smoke$$}"
export DB_PASSWORD="smoke-$(date +%s)" GRAFANA_PASSWORD="smoke-$(date +%s)-g"
export TRADING_INGESTION_ENABLED=false TRADING_INGESTION_RUN_ON_STARTUP=false   # no network needed for the check
COMPOSE=(docker compose --profile observability)

cleanup() { "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }
trap 'rc=$?; if [ $rc -ne 0 ]; then "${COMPOSE[@]}" logs --tail=60 2>&1 | cut -c1-240; fi; cleanup; exit $rc' EXIT

fail() { echo "SMOKE FAILED: $*" >&2; exit 1; }
prom() { "${COMPOSE[@]}" exec -T prometheus wget -qO- "http://localhost:9090$1" </dev/null; }
wait_for() { # description, timeout seconds, command...
  local what=$1 t=$2; shift 2
  local end=$((SECONDS + t))
  until "$@" >/dev/null 2>&1; do
    [ $SECONDS -lt $end ] || fail "timeout waiting for: $what"
    sleep 5
  done
  echo "ok: $what"
}

"${COMPOSE[@]}" up -d --build

app_healthy() { [ "$(docker inspect --format '{{.State.Health.Status}}' "$("${COMPOSE[@]}" ps -q app)")" = healthy ]; }
wait_for "bot healthy" 240 app_healthy

target_up() { prom '/api/v1/targets' | grep -q '"job":"trading-bot".*"health":"up"'; }
wait_for "Prometheus scrapes the bot" 120 target_up

metric_present() { prom '/api/v1/query?query=trading_metrics_refresh_age_seconds' | grep -q '"result":\[{'; }
wait_for "business metrics are in Prometheus" 120 metric_present

watchdog_firing() { prom '/api/v1/alerts' | grep -q 'Watchdog'; }
wait_for "heartbeat alert is firing" 120 watchdog_firing

am_has_heartbeat() { "${COMPOSE[@]}" exec -T alertmanager wget -qO- http://localhost:9093/api/v2/alerts </dev/null | grep -q Watchdog; }
wait_for "Alertmanager received the heartbeat" 120 am_has_heartbeat

dashboard() { curl -fs -u "admin:$GRAFANA_PASSWORD" 'http://127.0.0.1:3000/api/dashboards/uid/trading-overview' | grep -q 'Trading overview'; }
wait_for "Grafana has the dashboard" 120 dashboard

# Explicit time range (last hour). The search without one found no traces on Tempo 2.10 and 3.1 (PR #58); the cause
# was not confirmed, so this is an attempt: if CI on those versions still fails, the problem is somewhere else.
traces() {
    local now; now=$(date +%s)
    "${COMPOSE[@]}" exec -T tempo wget -qO- "http://localhost:3200/api/search?limit=1&start=$((now - 3600))&end=$((now + 60))" \
        </dev/null | grep -q traceID
}
wait_for "Tempo received traces" 180 traces

# the database must not be reachable from outside the compose network
if (exec 3<>/dev/tcp/127.0.0.1/5432) 2>/dev/null; then fail "PostgreSQL is published on the host"; fi
echo "ok: PostgreSQL is not published"

echo "SMOKE OK"

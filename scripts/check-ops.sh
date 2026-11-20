#!/usr/bin/env bash
# Static checks of the operations config. No secrets needed: placeholders are used for the required variables.
# Used by `make check-ops` and by CI.
set -euo pipefail
cd "$(dirname "$0")/.."

# MSYS_NO_PATHCONV: keep Git Bash on Windows from rewriting the container paths
export MSYS_NO_PATHCONV=1
PROM=prom/prometheus:v3.5.0@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996
AM=prom/alertmanager:v0.28.1@sha256:27c475db5fb156cab31d5c18a4251ac7ed567746a2483ff264516437a39b15ba

echo "== docker compose config"
DB_PASSWORD=x GRAFANA_PASSWORD=x docker compose --profile observability config -q

echo "== shell scripts parse"
for f in ops/backup/backup-loop.sh ops/alertmanager/entrypoint.sh scripts/*.sh; do sh -n "$f"; done

echo "== promtool: config and rules"
docker run --rm --entrypoint promtool -v "$PWD/ops/prometheus:/etc/prometheus:ro" "$PROM" \
  check config /etc/prometheus/prometheus.yml

echo "== alertmanager: rendered config (Telegram + heartbeat, and without both)"
for mode in full bare; do
  if [ "$mode" = full ]; then
    ENVS="-e TELEGRAM_BOT_TOKEN=123:abc -e TELEGRAM_CHAT_ID=-1001234 -e HEARTBEAT_URL=https://hc-ping.example/uuid"
  else
    ENVS=""
  fi
  # render with the real entrypoint, then validate with amtool instead of starting the server
  # shellcheck disable=SC2086
  docker run --rm $ENVS --entrypoint sh -v "$PWD/ops/alertmanager:/ops/alertmanager:ro" "$AM" -c \
    'sed "s#^exec .*#amtool check-config /tmp/alertmanager.yml#" /ops/alertmanager/entrypoint.sh > /tmp/e.sh && sh /tmp/e.sh'
done

echo "== grafana dashboards are valid JSON"
for f in ops/grafana/dashboards/*.json; do node -e 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))' "$f"; done

echo "ops config OK"

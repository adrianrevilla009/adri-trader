#!/usr/bin/env bash
# Static checks of the operations config. No secrets needed: placeholders are used for the required variables.
# Used by `make check-ops` and by CI.
set -euo pipefail
cd "$(dirname "$0")/.."

# MSYS_NO_PATHCONV: keep Git Bash on Windows from rewriting the container paths
export MSYS_NO_PATHCONV=1
PROM=prom/prometheus:v3.16.0@sha256:e04987641b57704f408e31f6373ba4138ecf27cb4a165d24aabb6c38fddd0f06
AM=prom/alertmanager:v0.35.0@sha256:e638797febd034838eca8b98cdbd46f951e9bf7f178777aa8669167406a9946c

echo "== docker compose config"
DB_PASSWORD=x GRAFANA_PASSWORD=x docker compose --profile observability config -q

echo "== shell scripts parse"
# the scripts that run inside busybox containers must be plain POSIX sh; the host scripts are bash
for f in ops/backup/backup-loop.sh ops/alertmanager/entrypoint.sh ops/offsite/ship-backups.sh; do sh -n "$f"; done
for f in scripts/*.sh; do bash -n "$f"; done

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

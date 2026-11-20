#!/bin/sh
# Builds the Alertmanager config from the environment, then starts it.
#   TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID  alerts go to Telegram (same bot as the application's own alerts)
#   HEARTBEAT_URL                          the always-firing Watchdog alert is POSTed here every 5 minutes (a
#                                          dead-man's-switch such as healthchecks.io: it alerts when the pings stop)
# Without Telegram the alerts are only visible in Prometheus/Alertmanager; a warning says so on every start.
set -eu

CFG=/tmp/alertmanager.yml
TOKEN_FILE=/tmp/telegram_token

if [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ]; then
  # file-based token: it never appears in the rendered config
  umask 077
  printf '%s' "$TELEGRAM_BOT_TOKEN" > "$TOKEN_FILE"
  case "$TELEGRAM_CHAT_ID" in
    ''|*[!0-9-]*) echo "TELEGRAM_CHAT_ID must be a number" >&2; exit 1 ;;
  esac
  PAGE_RECEIVER=telegram
else
  echo "WARNING: TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not set: alerts will not reach you" >&2
  PAGE_RECEIVER=blackhole
fi

{
  cat <<EOF
global:
  resolve_timeout: 5m

route:
  receiver: $PAGE_RECEIVER
  group_by: [alertname, agent]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
    - matchers: [alertname="Watchdog"]
      receiver: heartbeat
      group_wait: 0s
      group_interval: 1m
      repeat_interval: 5m
    - matchers: [severity="critical"]
      receiver: $PAGE_RECEIVER
      repeat_interval: 1h

inhibit_rules:
  # When the bot is down everything derived from it is noise
  - source_matchers: [alertname="BotDown"]
    target_matchers: [alertname=~"DatabaseUnreachableFromBot|MetricsRefreshFailing|ArenaNotRunning|MarketDataStale"]

receivers:
  - name: blackhole
EOF
  if [ "$PAGE_RECEIVER" = telegram ]; then
    cat <<EOF
  - name: telegram
    telegram_configs:
      - bot_token_file: $TOKEN_FILE
        chat_id: $TELEGRAM_CHAT_ID
        parse_mode: ""
        send_resolved: true
        message: |-
          {{ if eq .Status "firing" }}ALERT{{ else }}RESOLVED{{ end }} [{{ .CommonLabels.severity }}] {{ .CommonLabels.alertname }}
          {{ range .Alerts }}{{ .Annotations.summary }}
          {{ if .Annotations.description }}{{ .Annotations.description }}
          {{ end }}{{ end }}
EOF
  fi
  if [ -n "${HEARTBEAT_URL:-}" ]; then
    cat <<EOF
  - name: heartbeat
    webhook_configs:
      - url: $HEARTBEAT_URL
        send_resolved: false
EOF
  else
    echo "  - name: heartbeat"
  fi
} > "$CFG"

exec /bin/alertmanager --config.file="$CFG" --storage.path=/alertmanager --web.listen-address=:9093

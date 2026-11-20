#!/bin/sh
# Nightly PostgreSQL backup inside the compose network. A dump only counts if it is non-empty and readable by
# pg_restore; only then the marker file /backups/last_success is touched, and the bot exports its age as a metric
# (trading_backup_age_seconds) so a silent failure becomes an alert. One backup also runs at start.
set -eu

DIR=/backups
HOUR="${BACKUP_HOUR_UTC:-03}"
MIN="${BACKUP_MINUTE:-15}"
KEEP="${BACKUP_KEEP:-14}"

run_backup() {
  stamp="$(date -u +%Y%m%d-%H%M%S)"
  tmp="$DIR/.trading-$stamp.part"
  out="$DIR/trading-$stamp.dump"
  if ! pg_dump -Fc > "$tmp"; then
    rm -f "$tmp"; echo "backup FAILED: pg_dump error" >&2; return 1
  fi
  if [ ! -s "$tmp" ]; then
    rm -f "$tmp"; echo "backup FAILED: empty dump" >&2; return 1
  fi
  if ! pg_restore -l < "$tmp" > /dev/null; then
    rm -f "$tmp"; echo "backup FAILED: unreadable dump" >&2; return 1
  fi
  mv "$tmp" "$out"
  # retention: everything but the newest $KEEP dumps
  ls -1t "$DIR"/trading-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm -f
  touch "$DIR/last_success"
  echo "backup ok: $out"
}

run_backup || true
while true; do
  if [ "$(date -u +%H:%M)" = "$HOUR:$MIN" ]; then
    run_backup || true
    sleep 61
  fi
  sleep 20
done

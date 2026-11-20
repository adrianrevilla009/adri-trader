#!/usr/bin/env bash
# Daily PostgreSQL backup for the docker-compose database. Keeps the last N dumps.
# Usage: scripts/backup.sh [backup_dir] [keep]   (cron: 15 3 * * *)
# Restore test: scripts/restore-check.sh <dump file>
set -euo pipefail

DIR="${1:-./backups}"
KEEP="${2:-14}"
DB="${DB_NAME:-trading}"
USER_NAME="${DB_USER:-trading}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DIR/trading-$STAMP.dump"

mkdir -p "$DIR"
docker compose exec -T postgres pg_dump -U "$USER_NAME" -Fc "$DB" > "$OUT"

# an empty or truncated dump must never count as a backup
if [ ! -s "$OUT" ]; then
  rm -f "$OUT"
  echo "backup failed: empty dump" >&2
  exit 1
fi
docker compose exec -T postgres pg_restore -l < "$OUT" > /dev/null || { rm -f "$OUT"; echo "backup failed: unreadable dump" >&2; exit 1; }

# retention: delete everything but the newest $KEEP dumps
ls -1t "$DIR"/trading-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm -f
echo "backup ok: $OUT"

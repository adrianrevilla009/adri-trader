#!/usr/bin/env bash
# Restores a dump into a throwaway database and prints row counts of the key tables.
# Compare the order and tax ledger counts with eToro before trusting a backup.
# Usage: scripts/restore-check.sh <dump file>
set -euo pipefail

DUMP="${1:?usage: restore-check.sh <dump file>}"
USER_NAME="${DB_USER:-trading}"
TMP_DB="restore_check_$$"

docker compose exec -T postgres createdb -U "$USER_NAME" "$TMP_DB"
trap 'docker compose exec -T postgres dropdb -U "$USER_NAME" --if-exists "$TMP_DB"' EXIT
docker compose exec -T postgres pg_restore -U "$USER_NAME" -d "$TMP_DB" --no-owner < "$DUMP"
for t in instrument bar_daily trade_order fill cash_ledger kill_switch arena_agent_day; do
  n=$(docker compose exec -T postgres psql -U "$USER_NAME" -d "$TMP_DB" -Atc "SELECT count(*) FROM $t" 2>/dev/null || echo "missing")
  echo "$t: $n"
done

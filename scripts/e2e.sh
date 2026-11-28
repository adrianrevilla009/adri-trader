#!/usr/bin/env bash
# End-to-end test: builds the bot, starts a throwaway PostgreSQL, runs the real jar, drives it with the real client of
# the screen (frontend/src/api.ts) with real MFA codes, restarts it, and checks the database. Nothing touches your data.
# Needs Docker, Java 21, Maven and Node. Run it with `make e2e`.
set -euo pipefail
cd "$(dirname "$0")/.."
docker() { MSYS_NO_PATHCONV=1 command docker "$@"; }   # Git Bash rewrites paths in container arguments; Maven needs them left alone

NAME="e2e-pg-$$"
PORT_API=${E2E_API_PORT:-18100}
PORT_HTTP=${E2E_HTTP_PORT:-18080}
TOKEN="e2e-token-$(date +%s)-abcdef"
DBPASS="e2e-$(date +%s)"
SECRET=$(head -c 20 /dev/urandom | base32 | tr -d '=\n')
APP_PID=""
LOG="$(mktemp)"

cleanup() {
  if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null || true; fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap 'rc=$?; if [ $rc -ne 0 ]; then echo "--- bot log (tail) ---"; tail -40 "$LOG" | cut -c1-220; fi; cleanup; rm -f "$LOG"; exit $rc' EXIT

echo "== build the bot"
mvn -q -DskipTests package -pl trading-app -am
JAR=$(ls trading-app/target/trading-app-*.jar | grep -v original | head -1)

echo "== start PostgreSQL"
docker run -d --name "$NAME" -e POSTGRES_PASSWORD="$DBPASS" -e POSTGRES_DB=trading -p 127.0.0.1::5432 postgres:16-alpine >/dev/null
DBPORT=$(docker port "$NAME" 5432/tcp | head -1 | sed 's/.*://')
for _ in $(seq 1 60); do docker exec "$NAME" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
sleep 2

start_bot() {
  DB_URL="jdbc:postgresql://127.0.0.1:$DBPORT/trading" DB_USER=postgres DB_PASSWORD="$DBPASS" \
  SERVER_PORT=$PORT_HTTP TRADING_API_TOKEN="$TOKEN" TRADING_API_PORT=$PORT_API \
  TRADING_PROD_ENABLED=true TRADING_PROD_MFA_SECRET="$SECRET" \
  TRADING_ETORO_INSTRUMENTS="IE00B4L5Y983=15348,IE00B5BMR087=3275" \
  TRADING_INGESTION_ENABLED=false TRADING_INGESTION_RUN_ON_STARTUP=false TRADING_MONITORING_PORT=0 \
  java -jar "$JAR" >>"$LOG" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 90); do
    if curl -fs -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT_API/api/system" >/dev/null 2>&1; then return 0; fi
    kill -0 "$APP_PID" 2>/dev/null || { echo "the bot stopped"; return 1; }
    sleep 1
  done
  echo "timeout waiting for the bot"
  return 1
}

export E2E_API_URL="http://127.0.0.1:$PORT_API" E2E_API_TOKEN="$TOKEN" E2E_MFA_SECRET="$SECRET"
start_bot

cd frontend
if [ ! -d node_modules ]; then npm ci --ignore-scripts --silent; fi

echo "== phase 1: the client against the running bot"
npx vitest run --config vitest.e2e.config.ts e2e/phase1.e2e.test.ts

echo "== restart the bot"
kill "$APP_PID"
wait "$APP_PID" 2>/dev/null || true
cd ..
start_bot
cd frontend

echo "== phase 2: state after the restart"
npx vitest run --config vitest.e2e.config.ts e2e/phase2.e2e.test.ts

echo "== the database"
q() { docker exec "$NAME" psql -U postgres -d trading -tAc "$1"; }
check() { # description, expected-ok(0/1) from a SQL boolean
  [ "$(q "$2")" = "t" ] || { echo "DATABASE CHECK FAILED: $1"; exit 1; }
}
check "the agent row exists" "select count(*) = 1 from agent where id = 'e2e-trend'"
check "exactly one switch to PROD was recorded" "select count(*) = 1 from mode_audit where outcome = 'SWITCHED' and to_mode = 'PROD'"
check "exactly one switch back to DEMO was recorded" "select count(*) = 1 from mode_audit where outcome = 'SWITCHED' and to_mode = 'DEMO'"
check "the rejections were recorded" "select count(*) >= 4 from mode_audit where outcome = 'REJECTED'"
check "the lockout was recorded" "select count(*) >= 1 from mode_audit where outcome = 'LOCKED_OUT'"
check "no code leaked into the audit" "select count(*) = 0 from mode_audit where detail ~ '\m[0-9]{6}\M'"
if q "update mode_audit set actor = 'x'" >/dev/null 2>&1; then echo "DATABASE CHECK FAILED: the audit accepted an UPDATE"; exit 1; fi
echo "E2E OK"

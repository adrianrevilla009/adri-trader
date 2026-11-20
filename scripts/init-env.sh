#!/usr/bin/env bash
# Creates .env from .env.example with random passwords, once. Never overwrites an existing .env.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then echo ".env already exists: left untouched"; exit 0; fi
# tr is killed by SIGPIPE when head has enough: that is expected, so pipefail is off for this one pipeline
rand() { set +o pipefail; LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32; }
sed -e "s/^DB_PASSWORD=.*/DB_PASSWORD=$(rand)/" -e "s/^GRAFANA_PASSWORD=.*/GRAFANA_PASSWORD=$(rand)/" \
    -e "s/^KILL_SWITCH_TOKEN=.*/KILL_SWITCH_TOKEN=$(rand)/" .env.example > .env
chmod 600 .env 2>/dev/null || true
echo ".env created with generated passwords. Add TELEGRAM_* and the eToro keys when you have them."

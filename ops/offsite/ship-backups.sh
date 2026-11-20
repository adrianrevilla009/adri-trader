#!/bin/sh
# Copies the database dumps from the compose `backups` volume to Azure Blob Storage (off the machine). Run nightly by
# the systemd timer that cloud-init installs. Authenticates with the VM's managed identity: no key is stored anywhere.
# Dumps already uploaded are skipped (--overwrite false), so re-running is safe.
#
#   STORAGE_ACCOUNT, CONTAINER   set by the systemd unit
#   COMPOSE_PROJECT              compose project name, default adri-trader (the directory name)
set -eu

: "${STORAGE_ACCOUNT:?STORAGE_ACCOUNT is not set}"
: "${CONTAINER:?CONTAINER is not set}"
PROJECT="${COMPOSE_PROJECT:-adri-trader}"
# pinned by digest like every other image
AZ_CLI=mcr.microsoft.com/azure-cli:2.77.0@sha256:d41153958a61392a4c9db1591e33b533ca8ab8b59529d212d06bb6ec7e9c1f25

docker run --rm -v "${PROJECT}_backups:/backups:ro" -e STORAGE_ACCOUNT -e CONTAINER "$AZ_CLI" sh -c '
  set -eu
  az login --identity --output none
  [ -n "$(ls /backups/trading-*.dump 2>/dev/null)" ] || { echo "no dumps to ship" >&2; exit 1; }
  az storage blob upload-batch --account-name "$STORAGE_ACCOUNT" --destination "$CONTAINER" --source /backups \
    --pattern "trading-*.dump" --auth-mode login --overwrite false --output none
  echo "off-machine backup ok"
'

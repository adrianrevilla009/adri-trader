#!/usr/bin/env bash
# Runs the factory Semgrep policies (policies/semgrep) over the production code, the same check CI runs.
# It copies the sources into a throwaway container instead of bind-mounting the repository: on Windows and macOS a bind
# mount makes Semgrep take about ten minutes, a copy takes about twenty seconds.
#   scripts/semgrep.sh            scan (any finding fails)
#   scripts/semgrep.sh --test     run the tests of the rules themselves
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # keep Git Bash on Windows from rewriting container paths
IMAGE=semgrep/semgrep@sha256:67518c174185a2043fdbed16c70631b79209499b3671178e7194379b48295a0d
NAME="semgrep-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT

docker run -d --name "$NAME" --entrypoint sleep "$IMAGE" 1800 >/dev/null
docker exec "$NAME" mkdir -p /src
tar --exclude=target -cf - policies trading-*/src infra/terraform | docker cp - "$NAME":/src

if [ "${1:-}" = "--test" ]; then
  docker exec -w /src "$NAME" semgrep --metrics=off --disable-version-check --test policies/semgrep
else
  dirs=$(ls -d trading-*/src | tr '\n' ' ')
  # shellcheck disable=SC2086
  docker exec -w /src "$NAME" semgrep --metrics=off --disable-version-check --config policies/semgrep --error --quiet $dirs infra/terraform
  echo "semgrep: no findings"
fi

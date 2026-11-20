#!/usr/bin/env bash
# Deploys a released version on the server: pulls the image, VERIFIES its signature and provenance, and only then
# starts it, by digest (a tag can be moved, a digest cannot). Run on the VM from the repository directory.
#
#   scripts/deploy.sh 0.1.0
#
# An image that is not signed by this repository's release workflow is never started.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: scripts/deploy.sh <version, e.g. 0.1.0>}"
REPO="${GITHUB_REPOSITORY:-adrianrevilla009/adri-trader}"
IMAGE="ghcr.io/${REPO}"
# pinned by digest: the verifier itself must not be a moving target
COSIGN=ghcr.io/sigstore/cosign/cosign:v3.1.4@sha256:0f06154836d2de34f887a5c273506d52ca43edaa1d9e53e6510fa78a48f69c2d

[ -f .env ] || { echo "missing .env: run 'make init' and add the secrets (see docs/observability.md)" >&2; exit 1; }

echo "== pulling ${IMAGE}:${VERSION}"
docker pull -q "${IMAGE}:${VERSION}" > /dev/null
REF="$(docker inspect --format '{{index .RepoDigests 0}}' "${IMAGE}:${VERSION}")"
echo "   digest: ${REF}"

echo "== verifying signature (must come from the release workflow of ${REPO} on a v* tag)"
docker run --rm "$COSIGN" verify "$REF" \
  --certificate-identity-regexp "^https://github.com/${REPO}/\.github/workflows/release\.yml@refs/tags/v.*$" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com > /dev/null

# Build provenance is attested with GitHub attestations; check it by hand with:
#   gh attestation verify oci://${REF} --repo ${REPO}
# It is not part of this gate because that command needs the GitHub CLI and a login on the server.

echo "== starting"
APP_IMAGE="$REF" docker compose --profile observability up -d --no-build
echo "${REF}  deployed $(date -u +%FT%TZ)" >> deployed.log
echo "deployed ${REF}"

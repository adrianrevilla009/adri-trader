# Security policy

This project can send orders to a broker with real money. Treat any weakness as serious.

## Reporting a vulnerability

Do not open a public issue. Use GitHub's private reporting: **Security > Report a vulnerability** on this repository.
Include what you found, how to reproduce it, and what it could affect. Never include real keys or account data.

## What is in place

- Secrets only in environment variables or the environment's own secrets, never in the repository or the image.
- Secret scanning with push protection, gitleaks over the full history in CI, and a local pre-commit check.
- Dependency, image and configuration scanning on every pull request; images are signed (cosign) with build
  provenance and an SBOM, and an unsigned image is never promoted to an environment.
- CodeQL static analysis on the Java code and on the workflow files.
- The kill switch and status endpoints listen on the loopback address only.

See [docs/ci-cd.md](docs/ci-cd.md) for the pipeline and [docs/runbook.md](docs/runbook.md) for operations.

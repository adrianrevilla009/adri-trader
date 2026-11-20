## What and why

<!-- One or two sentences. The PR title is the commit message: use Conventional Commits (feat, fix, docs, test, ci, chore, refactor, perf, build). -->

## Risk to money or data

<!-- Does this touch execution, risk, the broker, the ledger, taxes or migrations? What could go wrong, and what stops it? -->
- [ ] Does not touch money paths
- [ ] Touches money paths: the failure case is tested (not only the happy path)

## Checklist

- [ ] Tests added or updated (unit, and integration or concurrency when state is shared)
- [ ] Nothing assumed about eToro is presented as verified (see `docs/fase0-verificacion-etoro.md`)
- [ ] `README.md` and `docs/` updated if behavior, modules, configuration or status changed
- [ ] No secrets, tokens or personal data in code, tests, docs or logs
- [ ] Database changes are a new Flyway migration (never edit an applied one)
- [ ] Any accepted vulnerability or scanner exception has a reason and an expiry (`.trivyignore`)

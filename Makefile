# Market data is never committed: `make up` starts the whole stack (database, bot, nightly backups, Prometheus,
# Alertmanager, Grafana, Tempo, OpenTelemetry collector). The bot downloads the history on its first start
# (idempotent; later starts only add new days). Everything lives in docker volumes.
.PHONY: init up up-core down reset logs ps psql test hooks check-ops smoke

## Create .env with generated passwords (once; never overwrites)
init:
	bash scripts/init-env.sh

## Whole stack. Grafana on http://127.0.0.1:3000 (user admin, password GRAFANA_PASSWORD in .env)
up: init
	docker compose --profile observability up -d --build

## Only database, bot and backups (no dashboards)
up-core: init
	docker compose up -d --build

## Stop everything; the data stays in the volumes
down:
	docker compose --profile observability down

## Stop and DELETE all stored data, including the backups volume (next `make up` downloads the history again)
reset:
	docker compose --profile observability down -v

logs:
	docker compose --profile observability logs -f --tail=100 app

ps:
	docker compose --profile observability ps

psql:
	docker compose exec postgres sh -c 'psql -U "$$POSTGRES_USER" -d trading'

test:
	mvn verify

## Validate the compose file and the Prometheus/Alertmanager config (the same check CI runs)
check-ops:
	bash scripts/check-ops.sh

## Start a throwaway copy of the whole stack, check that every piece works together, remove it (about 3 minutes)
smoke:
	bash scripts/smoke-stack.sh

## Enable the local git hooks (conventional commits, secret checks, no direct pushes to main)
hooks:
	git config core.hooksPath .githooks
	@echo "Hooks enabled"

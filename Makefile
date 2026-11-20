# Market data is never committed: `make up` starts PostgreSQL and the app, which downloads the history on the
# first run (idempotent; the daily job only adds new days). Data lives in the docker volume `pgdata`.
include config/dev-universe.env
export

JAR := trading-app/target/trading-app-0.1.0-SNAPSHOT.jar

.PHONY: up down reset test build hooks

build:
	mvn -q -pl trading-app -am package -DskipTests

## Start PostgreSQL and the app with the initial data download (Yahoo, read-only GET requests)
up: build
	docker compose up -d postgres
	TRADING_INGESTION_ENABLED=true TRADING_INGESTION_SOURCE=yahoo TRADING_INGESTION_FROM_YEAR=2009 \
	TRADING_INGESTION_RUN_ON_STARTUP=true TRADING_ARENA_ENABLED=true java -jar $(JAR)

## Stop the database; the data stays in the volume
down:
	docker compose down

## Stop and DELETE all stored data (next `make up` downloads it again)
reset:
	docker compose down -v

test:
	mvn verify

## Enable the local git hooks (conventional commits, secret checks, no direct pushes to main)
hooks:
	git config core.hooksPath .githooks
	@echo "Hooks enabled"

# syntax=docker/dockerfile:1
# Base images are pinned by digest (reproducible). Dependabot proposes updates to the tag and digest.

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY . .
# Tests already ran in CI before the image is built; this stage only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -Djacoco.skip=true -pl trading-app -am package \
    && cp trading-app/target/trading-app-*.jar /app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
# Pick up fixed Alpine packages published after the pinned base digest (keeps the vulnerability gate green).
RUN apk upgrade --no-cache
# Unprivileged user; nothing is written outside /app/reports.
RUN addgroup -S bot && adduser -S -G bot -h /app bot && mkdir -p /app/reports && chown -R bot:bot /app
USER bot
WORKDIR /app
COPY --from=build --chown=bot:bot /app.jar /app/app.jar

# Secrets come only from environment variables at run time (never baked into the image).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# The kill switch and status endpoints listen on the loopback address of the container: reach them with docker exec.
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8098/status || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

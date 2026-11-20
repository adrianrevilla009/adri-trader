# CLAUDE.md — etf-trading-bot

Bot de trading de ETF UCITS (eToro, EUR, Xetra, 100 € de capital, decisión diaria, solo largos). Maneja dinero: lo que no se
ha verificado se declara «no comprobado». Documentos: `docs/brief.md` (qué), `docs/PRD.md`, `docs/TRD.md` (cómo),
`docs/adr/` (decisiones; un ADR por decisión), `docs/runbook.md`, `docs/observability.md`, `docs/ci-cd.md`.

## Estándar del proyecto: ai-project-factory

El proyecto adopta las guías de <https://github.com/adrianrevilla009/ai-project-factory> (`.claude/skills/<nombre>/SKILL.md`,
`standards/`, `policies/`). **Léelas antes de diseñar o escribir código nuevo**, sobre todo: `hexagonal`, `ddd-tactical`, `cqrs`,
`maven-multimodule-layout`, `layering-rules-archunit`, `enforcer-rules`, y `standards/04-spring-platform.md` y
`10-observability-app.md`. Las excepciones a la fábrica están en el brief y en [ADR-0025](docs/adr/0025-arquitectura-hexagonal.md).

## Arquitectura (ADR-0025): hexagonal en multimódulo

`adapter → application → domain`. Nunca al revés.

| Dónde | Qué va |
| --- | --- |
| `trading-core/-data/-strategies/-risk/-backtest/-execution/-arena/-tax/-broker-sim` | Un contexto cada uno: paquetes `domain`, `application` y, solo si usan únicamente el JDK, `adapter.out.*`. **Sin Spring, JDBC, Jackson, HTTP ni Micrometer** (Maven Enforcer lo impide también de forma transitiva) |
| `trading-application` | Casos de uso entre contextos: puertos de entrada (`application.port.in`), servicios (`application.service`), puertos de salida (`application.port.out`) y DTO de lectura (`application.read`) |
| `trading-adapter-*` | Una tecnología cada uno: persistencia (JDBC y Flyway), marketdata, notification, web, metrics |
| `trading-broker-etoro` | Adaptador de eToro. `trading-broker-sim` es **dominio** (modelo del mercado) |
| `trading-app` | Solo arranque: `@SpringBootApplication`, `app.config` (cableado, `@ConfigurationProperties` en records), disparadores `@Scheduled`, CLI. **Sin lógica de negocio** |

Reglas al añadir código:

- Un puerto nuevo es una **interfaz** en `…application.port.in|out` (o en `domain` si el dominio lo necesita); su implementación va en
  un adaptador. Un caso de uso nuevo = un puerto de entrada + un servicio que lo implementa.
- Lectura y escritura separadas (CQRS de nivel 1): consultas por puertos de lectura con DTO propios; los comandos son casos de uso.
- Una clase de dominio no se modifica desde fuera: verbos del lenguaje del dominio, estado privado (`Agent` es el modelo).
- Configuración: `@ConfigurationProperties` sobre records con validación en el constructor; **no** `@Value`.
- Dinero con `BigDecimal`; `double` solo en estadística. Tiempo con `Clock` inyectado, UTC. Sin Lombok (records y constructores).
- Los adaptadores no dependen unos de otros; se encuentran solo a través de puertos.

**Las pruebas ArchUnit (`trading-app/src/test/.../architecture`) son un contrato. No relajes una regla para que pase el build:
corrige el código o escribe un ADR que cambie la regla.** Cada regla tiene una prueba negativa; si añades una regla, añade la suya.

## Comandos

- `make up` levanta todo (PostgreSQL, bot, copias, Prometheus, Alertmanager, Grafana, Tempo, OTel); `make down`, `make reset`.
- `make test` = `mvn verify` (Testcontainers: necesita Docker). `make check-ops` valida Compose y alertas. `make semgrep` pasa las políticas estáticas de la fábrica (una excepción lleva `// nosemgrep: regla -- motivo`). `make smoke` prueba la pila completa.
- `make hooks` activa los hooks locales (commits convencionales, sin secretos, sin commits ni pushes a `main`).

## Convenciones

- Conventional Commits (el asunto, ≤ 100 caracteres, es el título del PR). Código e identificadores en inglés; documentación en español.
- Un cambio llega a `main` solo por PR con el check `CI gate` y CodeQL en verde. Squash merge.
- Secretos solo en variables de entorno / `.env` (git-ignored); nunca en el repositorio, en la imagen ni en el chat.
- Alertas: `severity` es `page`, `ticket` o `info`; todas con `summary`, `description` y `runbook_url`.
- Una decisión nueva = un ADR en `docs/adr/` (y su fila en el índice). Las aceptadas no se reescriben: se marcan como sustituidas.

## Lo que no está verificado (no lo des por hecho)

La API real de eToro (13 suposiciones en `docs/fase0-verificacion-etoro.md`); los ISIN de `config/dev-universe.env`; la entrega de
alertas a Telegram; la VM de Azure y `scripts/deploy.sh` (nunca ejecutados); los flujos de release y promoción (sin primera
etiqueta). Ningún criterio de salida de fase está cumplido.

# 0009. Despliegue: Docker Compose en un servidor Linux pequeño

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Un proceso diario no necesita Kubernetes. La fábrica ai-project-factory lo propone por defecto (su ADR-0005).

## Decisión

Desarrollo en local; operación en un VPS o mini PC Linux con Docker Compose (aplicación + PostgreSQL). Es una excepción a la ADR-0005 de la fábrica, a justificar en el Gate 1.

## Consecuencias

- Un único comando levanta todo; solo se necesita salida HTTPS.
- Sin orquestación ni alta disponibilidad; la resiliencia es tier 3 (backup y restauración, 24 h).

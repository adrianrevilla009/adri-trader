# 0011. Alertas e informes: Telegram e informe HTML nocturno

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Hace falta la mínima infraestructura para operar con confianza.

## Decisión

Alertas y resumen diario por la API de bots de Telegram; informe HTML estático nocturno; heartbeat externo a 24 h. Grafana sobre PostgreSQL es opcional tras la fase 4.

## Consecuencias

- Sin frontend en la v1.
- El token de Telegram es un secreto en variable de entorno.

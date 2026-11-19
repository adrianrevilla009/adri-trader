# 0013. Tiempo: Clock inyectable, UTC y calendario por bolsa

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Los tests deben ser deterministas y no puede haber errores por cambio de hora.

## Decisión

`java.time` con `Clock` inyectable, UTC en base de datos y calendario por bolsa (`trading_calendar`).

## Consecuencias

- Prohibido `Instant.now()` o `LocalDate.now()` directos en el dominio.
- Los festivos y las sesiones parciales se modelan por bolsa.

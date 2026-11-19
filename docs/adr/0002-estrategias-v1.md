# 0002. Estrategias v1

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Hace falta cubrir del largo plazo a días y semanas sin depender de acciones sueltas.

## Decisión

Cuatro estrategias: tendencia (media de 200 días con banda de histéresis), momentum con rotación (mensual), volatilidad objetivo (mensual) y swing sobre ETFs (RSI de 2 días con filtro de tendencia). Todas implementan la interfaz única `Strategy` (ADR-0021).

## Consecuencias

- La arena de la fase 4 parte de este conjunto.
- Cada variante probada se anota en `trial_log` para subir el umbral de aceptación según el número de intentos.
- Si ninguna cumple el criterio de la fase 2, se descarta la familia.

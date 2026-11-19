# 0024. ta4j para señales y motor propio para la cartera

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Una estrategia de ta4j opera sobre una sola serie de precios, y las rotaciones entre ETFs deciden sobre varias a la vez.

## Decisión

ta4j aporta indicadores y métricas; el bucle de cartera, el modelo de costes y la ejecución simulada son propios (`trading-backtest`).

## Consecuencias

- Más código propio que mantener, pero control total sobre t/t+1 y costes.
- Los tests de series sintéticas del motor son obligatorios.

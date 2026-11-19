# 0021. Una sola interfaz de estrategia para backtest, arena y real

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Si el código difiere entre modos, el backtest no valida nada.

## Decisión

`Strategy.decide(MarketSnapshot, PortfolioState)` devuelve `TargetPortfolio` (pesos objetivo, suma ≤ 100 %). La estrategia no sabe en qué modo corre; el modo lo determina la implementación de `Broker` inyectada por configuración. Se diseña en la fase 2.

## Consecuencias

- Las estrategias son funciones puras y fáciles de testear (incluido el test de no anticipación).
- Todo el estado de ejecución vive fuera de la estrategia.

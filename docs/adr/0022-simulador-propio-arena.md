# 0022. La arena usa un simulador propio, no solo el demo del broker

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Las cuentas paper de un broker rellenan órdenes de forma optimista y sin impacto de mercado.

## Decisión

El demo de eToro sirve para probar conexión y órdenes. La comparación entre agentes usa `SimulatedBroker`, alimentado con datos reales y con comisiones, spread y slippage configurables.

## Consecuencias

- Comparaciones justas y reproducibles entre agentes.
- Hay que mantener el simulador alineado con el modelo de costes del backtest.

# 0004. Ejecución: apertura de t+1 con slippage; real con órdenes limitadas

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Operar en la subasta de apertura es impredecible; el demo de un broker suele rellenar de forma optimista.

## Decisión

En simulación se ejecuta al precio de apertura de t+1 con un slippage adicional prudente. En real se usan órdenes limitadas poco después de la apertura, evitando subastas (si la API real solo admite mercado, se registra y se mide). El agente sombra mide la diferencia y se recalibra el modelo. Las rotaciones escalonan primero la venta y luego la compra.

## Consecuencias

- Que las órdenes reales funcionen igual que en demo para una cuenta española es **no comprobado**: se verifica en fase 0 y con una orden real mínima.
- Requiere el modelo de costes configurable de la fase 2.

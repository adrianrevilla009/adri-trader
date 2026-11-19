# 0015. Umbrales de la fase 4: vivo frente a backtest

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Hace falta una definición medible de «el vivo coincide con el backtest».

## Decisión

Mismas decisiones que el backtest en el 95 % de los días y diferencia de patrimonio no superior al 0,5 % del capital; resultado por encima del percentil 90 de los agentes aleatorios; al menos 6 meses de arena (3 para detectar fallos técnicos).

## Consecuencias

- Una estrategia mensual toma solo 6 decisiones en 6 meses: la arena detecta errores, rara vez prueba ventaja; la prueba principal es el backtest largo.
- El Gate 4 (puerta al dinero real) se firma con estos criterios.

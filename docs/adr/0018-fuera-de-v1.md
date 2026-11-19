# 0018. Fuera de la v1

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Hay que acotar el alcance para poder validar el sistema.

## Decisión

Quedan fuera: acciones, divisas, noticias con LLM, ML sobre precios y meta-agente. Entran tras la fase 5 y en el orden de la fase 6: régimen de mercado, datos macro, meta-agente, noticias con LLM y, al final, ML. Cada capa va tras un interruptor y se acepta solo si mejora el resultado neto frente a la variante sin ella.

## Consecuencias

- Las noticias con LLM sufren contaminación por conocimiento posterior: se validan sobre todo hacia delante.
- `MarketSnapshot` reserva un campo `regime` para la fase 6.

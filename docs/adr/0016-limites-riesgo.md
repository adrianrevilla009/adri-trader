# 0016. Límites de riesgo iniciales

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

El módulo de riesgo debe ser independiente y sus valores no pueden ajustarse a mano tras una mala racha.

## Decisión

Máximo 25 % por activo, pérdida diaria máxima del 2 % y apagado del agente con una caída del 15 % desde máximos, en configuración YAML y aplicados por agente y a nivel de cuenta. Solo se modifican con un ADR.

## Consecuencias

- Valores conservadores; en real arrancan con la mitad del tamaño objetivo y tope de pérdida diaria menor.
- Un agente apagado por caída no se reactiva sin revisión humana.

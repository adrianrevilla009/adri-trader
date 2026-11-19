# 0010. Sesión del broker: clave de API por entorno y parada segura

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

No hay sesión interactiva ni doble factor periódico, pero la API beta puede cambiar, limitar o revocar el acceso.

## Decisión

Una clave de API por entorno (demo y real) guardada como secreto en variables de entorno. Si la API no responde, devuelve 429 sostenido o rechaza la clave, el bot no opera y avisa. Las órdenes se persisten antes de enviar y no se reenvían a ciegas: ante falta de respuesta se consultan órdenes y posiciones.

## Consecuencias

- El riesgo principal pasa a ser el cambio de la API beta: tests de contrato contra demo y runbook.
- eToro puede suspender el acceso ante tráfico anómalo: limitador de ritmo propio obligatorio.

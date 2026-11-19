# 0003. Ritmo: una decisión diaria tras el cierre de Xetra

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

El horizonte pautado es diario o semanal y no se quiere infraestructura de alta frecuencia.

## Decisión

Una decisión al día tras el cierre de Xetra y ejecución al día siguiente (t+1).

## Consecuencias

- Elimina streaming y eventos; la API REST se consulta, no avisa.
- La separación decisión/ejecución evita el sesgo de futuro.
- Sin capacidad para estrategias intradía.

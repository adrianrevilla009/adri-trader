# 0020. Sin adaptador en Python

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

eToro ofrece API REST; no hay SDK que obligue a Python.

## Decisión

Se llama a eToro desde Java con el cliente HTTP estándar. Si un broker futuro solo ofreciera SDK en Python, iría como proceso aparte tras la interfaz `Broker`.

## Consecuencias

- Una pieza menos que operar.
- La interfaz `Broker` aísla cualquier cambio futuro.

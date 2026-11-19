# 0023. Persistencia: solo PostgreSQL

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

La fábrica propone persistencia políglota por defecto (su ADR-0013). A escala diaria, 20 ETFs durante 20 años son ~100.000 filas.

## Decisión

Solo PostgreSQL con migraciones Flyway; sin base de series temporales ni otras bases.

## Consecuencias

- Una sola pieza que respaldar y restaurar.
- Excepción a la ADR-0013 de la fábrica, registrada aquí.

# 0012. Stack: Java 21, Maven multimódulo, Spring Boot, PostgreSQL, ta4j, sin Lombok

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Coincide con las ADR-0002 y ADR-0027 de la fábrica. Java 21 es el mínimo de ta4j 0.22.

## Decisión

Java 21, Maven multimódulo, Spring Boot, PostgreSQL con Flyway, JDBC (`JdbcTemplate` o jOOQ), `BigDecimal` para dinero, ta4j para indicadores, JUnit 5 + AssertJ + Testcontainers, Logback. Sin Lombok (records y constructores). Un solo hilo de decisión por job con sondeo del estado de órdenes.

## Consecuencias

- SQL explícito y auditable para el ledger.
- Un hilo evita condiciones de carrera al reconciliar.

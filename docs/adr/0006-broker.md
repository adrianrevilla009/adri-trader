# 0006. Broker: eToro (API pública beta), primero en demo y sin depósito

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Con 100 € hacen falta 0 € de comisión, órdenes por importe y que la automatización esté permitida. Trading 212 la prohíbe en sus términos (cláusula 4.2(a)); IBKR rechaza órdenes fraccionadas por API y cuesta ~2,40 € por operación; XTB cerró su API en marzo de 2025; Alpaca, Freedom24, Trade Republic y Scalable no encajan (sin acceso, con 2 € por orden o sin API oficial).

## Decisión

Se usa eToro (cuenta en euros, ETFs reales sin apalancamiento: `leverage` 1 y `settlementType` `real`) con su API pública beta. IBKR queda como adaptador futuro opcional (`trading-broker-ib`) si el capital supera unos 2.000–5.000 €.

## Consecuencias

- La API está en beta: eToro puede denegar o revocar el acceso en cualquier momento; límites de 60 lecturas y 20 escrituras por minuto y clave.
- Sin clave de idempotencia nativa: ver ADR-0010 y TRD §8.3.
- No comprobado: que KYC y clave demo no exijan depósito, y que la API real esté habilitada para minoristas españoles.

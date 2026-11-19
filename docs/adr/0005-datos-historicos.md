# 0005. Datos históricos: fuente gratuita por defecto, ADR si hay que pagar

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

La profundidad de las velas de eToro no está comprobada y no se pagan licencias por defecto. Se necesitan 12 años o más para el universo.

## Decisión

Sin pago por defecto. Prueba de dos días en la fase 1 comparando Stooq y Yahoo entre sí: se elige la primera fuente con 12 años o más para el 90 % del universo y diferencias inferiores al 0,5 % entre ambas en el último año. Si ninguna cumple, un ADR posterior decide pagar un proveedor con tope mensual (EODHD, ~19,99 $/mes; un mes basta para descargar el histórico una vez).

## Consecuencias

- Esta ADR se **completa** con el resultado de la prueba en la fase 1 (fuente elegida).
- Raw y limpio separados; cada backtest registra el hash del dataset.

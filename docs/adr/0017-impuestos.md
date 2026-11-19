# 0017. Impuestos: TaxLedger revisado por asesor y aviso del modelo 720

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

En España cada venta con beneficio tributa (base del ahorro 19/21/23 %), no hay traspaso fiscal en ETFs, rige la regla de los dos meses y el FIFO. Esto no es asesoramiento fiscal.

## Decisión

`TaxLedger` calcula por ISIN con FIFO, marca pérdidas afectadas por la regla de los dos meses y exporta un CSV anual. Un asesor fiscal lo revisa antes de la primera declaración con operaciones reales. Aviso del modelo 720 al llegar a 40.000 € (umbral legal 50.000 € por categoría).

## Consecuencias

- Las métricas netas deben incluir el efecto fiscal de la rotación.
- Se alimenta del ledger de la fase 1, por lo que este debe ser fiable desde el principio.

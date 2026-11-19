# 0014. Umbrales de aceptación de la fase 2

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Los umbrales deben fijarse antes de ver resultados para no ajustarlos después.

## Decisión

Los últimos 3 años son holdout y se miran una sola vez. Walk-forward con 5 años de entrenamiento, 6 meses de test y paso de 6 meses (~8 ventanas con 12 años). Superar al benchmark neto en Sharpe o Calmar en el 70 % de las ventanas, estabilidad ante cambios de parámetros del 20 % y 30 operaciones o más en swing.

## Consecuencias

- Si ninguna estrategia cumple, el resultado válido es abandonar esa familia o quedarse con un ETF global.
- El número de variantes probadas (`trial_log`) puede elevar el umbral.

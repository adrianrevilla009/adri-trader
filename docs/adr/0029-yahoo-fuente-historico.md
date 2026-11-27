# 0029. Yahoo Finance como fuente del histórico, contrastada con eToro

- **Estado:** Aceptada
- **Fecha:** 2026-11-27
- **Fuente:** completa [ADR-0005](0005-datos-historicos.md); [resultados preliminares de la fase 2](../resultados-fase2-preliminar.md);
  [docs/fase0-verificacion-etoro.md](../fase0-verificacion-etoro.md)

## Contexto

La ADR-0005 pide elegir la primera fuente gratuita con 12 años o más para el 90 % del universo y diferencias inferiores al
0,5 % entre dos fuentes en el último año. Stooq bloquea las descargas automáticas (exige JavaScript), así que la comparación
entre Stooq y Yahoo no se pudo hacer. Hay otra segunda fuente utilizable para el último año: las velas diarias de eToro.

## Prueba (2026-11-27, solo lecturas)

Cierres diarios de Yahoo (`range=1y`) frente a las velas diarias de eToro (demo, `OneDay`), por fecha:

| Instrumento | Días comparados | Diferencia media | Diferencia máxima |
| --- | --- | --- | --- |
| EUNL.DE | 178 | 0,055 % | 0,29 % (2026-11-25) |
| SXR8.DE | 249 | 0,078 % | 0,49 % (2026-11-06) |
| IS3N.DE | 249 | 0,137 % | 0,97 % (2026-11-06) |

Un día de IS3N supera el 0,5 %; la media de los tres queda muy por debajo. Se interpreta como diferencia de momento del cierre
entre fuentes y no como un error de datos, pero **no se ha investigado la causa**.

## Decisión

- **Yahoo Finance es la fuente del histórico** (12 a 17 años para los ETF probados). No se paga ningún proveedor.
- La fuente es **no oficial** (puede cambiar o bloquear): la ingesta ya valida calidad, alerta si falta el dato del día y el
  ciclo diario no opera sin la barra de ese día.
- La comparación de dos fuentes se cumple **a medias**: la media pasa con holgura y el máximo no en un día. Se repetirá la
  comparación antes de la fase 5 (dinero real) y, si la diferencia media pasa del 0,5 %, se paga un mes de proveedor
  (EODHD, ~20 $) según la ADR-0005.
- Las velas de eToro llegan a ~793 días para SXR8 e IS3N y a ~179 para EUNL (se vio antes 176). No sustituyen a los 10+ años
  de Yahoo, pero sirven como comprobación cruzada reciente.

## Consecuencias

- El backtest sigue usando Yahoo; cada ejecución registra el hash del dataset (ADR-0005).
- **No verificado:** el calendario Xetra contra el oficial, la causa del día atípico de IS3N, y los 12 años para EUNK.DE (que
  además no existe como instrumento en eToro).

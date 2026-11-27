# 0028. Ciclo diario en la cuenta demo de eToro

- **Estado:** Aceptada
- **Fecha:** 2026-11-27
- **Fuente:** decisión del titular («hazlo todo de forma autónoma», con tope de gasto en pruebas); [PRD](../PRD.md) fase 3;
  [docs/fase0-verificacion-etoro.md](../fase0-verificacion-etoro.md); [ADR-0008](0008-escalado.md)

## Contexto

`TradingDayService` (reconciliar, leer datos, decidir, pasar el riesgo, enviar, registrar) estaba hecho y probado con el
simulador, pero nada lo lanzaba ni conectaba `EToroBroker` a la aplicación. La fase 3 pide 4 semanas en demo sin
intervención. Hacía falta decidir cuándo corre, qué estrategia usa mientras no haya una validada, cómo se trata el dólar
(eToro cuenta en USD, el proyecto razona en EUR) y qué impide gastar de más.

## Decisión

- **Solo DEMO.** `trading.etoro.mode` solo acepta `DEMO`; `REAL` hace fallar el arranque. El dinero real exige ADR-0008 y la
  puerta por agente (`RealTradingGate`, hoy cerrada).
- **Apagado por defecto** (`trading.live.enabled=false`). Se activa con `TRADING_LIVE_ENABLED=true`, claves de eToro en el
  entorno, un ISIN (`trading.live.isin`) y su id de eToro en `trading.etoro.instruments`; si falta algo, no arranca.
- **Cuándo:** lunes a viernes a las **19:15 Europe/Berlin**, después de la ingesta (19:00) y antes de la arena (19:30). La
  decisión usa el cierre del día; las órdenes de mercado esperan a la apertura siguiente. El día se reclama de forma
  atómica en base de datos: una segunda ejecución no hace nada.
- **Estrategia:** `buy-and-hold` sobre un solo ISIN. No hay ninguna estrategia que supere al benchmark (fase 2), así que este
  ciclo prueba la **mecánica** (órdenes, reconciliación, interruptor, alertas) y no busca rentabilidad. El riesgo la recorta al
  25 % por activo (ADR-0016).
- **Tope de gasto:** cada orden está limitada por `trading.etoro.max-order-amount` (10 por defecto) y se rechaza **antes de
  enviar nada**. El capital del agente (`trading.live.capital`, 40 por defecto) hace que una posición del 25 % coincida con
  ese tope. Subir cualquiera de los dos es un cambio de configuración consciente.
- **Moneda:** eToro informa el saldo y los importes en USD. En demo se usan **1 USD = 1 EUR** sin conversión; el error es
  de un orden del 8 % y es aceptable con dinero ficticio. **Antes de dinero real hay que decidir la conversión** (ADR
  nuevo); queda anotado como limitación en `EToroBroker.cash()`.
- **Capital visible:** `CashCappedBroker` limita el efectivo que ve el agente a su capital (la demo tiene 100000).

## Consecuencias

- El ciclo se puede encender en demo sin tocar código y empezar a contar las 4 semanas de la fase 3.
- Mientras la estrategia sea `buy-and-hold` no se mide ninguna rentabilidad útil; es una prueba de tuberías.
- Un 404 inmediato al consultar una orden recién creada se trata como «enviada, pendiente» (eToro tarda un instante en
  listarla); la cartera también tarda. Ver el documento de verificación de eToro.
- **No verificado:** el comportamiento de una orden de mercado enviada fuera de horario (esperada: queda en espera hasta la
  apertura), la cancelación, y los ISIN del universo contra el catálogo de eToro (los ids vienen de la búsqueda por símbolo).

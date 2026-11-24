# Fase 0: qué hay que verificar contra la API real de eToro

El cliente `EToroBroker` está construido y probado contra un servidor local, sin clave. Todo lo de esta lista es **no comprobado** y se confirma con la clave demo antes de dar la fase 0 por cerrada. Los puntos están aislados en `EToroConfig.Endpoints` y `EToroBroker`.

## Datos de partida (vienen del brief)

- Cabeceras `x-api-key`, `x-user-key`, `x-request-id` (UUID por petición). Se usa el id interno de la orden como `x-request-id`.
- Órdenes demo: `POST /api/v2/trading/execution/demo/orders`. Órdenes reales: `POST /api/v2/trading/execution/orders`.
- Límites: 60 lecturas y 20 escrituras por minuto y clave; 429 con `Retry-After`.
- Órdenes por importe (`amount`), `leverage` 1, `settlementType` `real`.

## Por verificar

| # | Qué | Dónde está en el código | Si es distinto |
| --- | --- | --- | --- |
| 1 | Forma del cuerpo de la orden (`instrumentId`, `isBuy`, `amount`, `leverage`, `settlementType`, `limitRate`) | `EToroBroker.orderBody` | Cambiar solo ese método |
| 2 | Forma de la respuesta de una orden (`orderId`) | `EToroBroker.submit` | Cambiar el parseo |
| 3 | Ruta y respuesta del coste estimado (what-if) y que informe del tipo de liquidación. **Verificado en parte (2026-11-24, clave real de solo lectura, consulta sin orden):** la ruta real es `POST /api/v2/trading/info/costs` (demo: `/api/v2/trading/info/demo/costs`), cuerpo plano (`action`, `transaction`, `instrumentId`, `settlementType`, `orderType` = `mkt`, `leverage`, `amount`, `orderCurrency` = `usd`) y la respuesta es `costs[]` con `costType`, `value` y `currency`. Para 100 USD de EUNL.DE devolvió `markup` 0, `marketSpread` 0,1 USD y `overnightFee` 0 EUR. **La respuesta NO trae `settlementType`**: el código actual (`Endpoints.estimate` = `/api/v2/trading/execution/estimate`, `preflight` exige `settlementType` = `real`) rechazaría todas las órdenes reales. El tipo de liquidación se comprueba con `POST /api/v2/trading/info/eligibility` (fila 10) | `Endpoints.estimate`, `EToroBroker.preflight` | Cambiar ruta, método, cuerpo y parseo, y pasar la comprobación del tipo de liquidación a elegibilidad. Sin esta comprobación no se envían órdenes reales |
| 4 | Ruta y forma de la consulta de una orden y sus estados. **Solo según la documentación, no probado** (no hay órdenes): `GET /api/v2/trading/info/orders:lookup?orderId=` o `?referenceId=` (demo: `/info/demo/orders:lookup`); `status` es un objeto `{id, name, errorCode, errorMessage}` con ids 1 Received, 2 Placed, 3 Filled, 4 Rejected, 5 PartiallyFilled, 6 PendingCancel, 7 Canceled, 8 Expired, 9 CanceledPartiallyFilled, 10 RejectedPartiallyFilled, 11 WaitingForMarket, 12 PendingTriggeredRate. El código usa `/api/v2/trading/execution/orders/{id}` y lee `status` como texto | `Endpoints.orderStatus`, `EToroBroker.state` | Ajustar el mapa de estados |
| 5 | Ruta y forma de cartera y efectivo. **Verificado en parte (2026-11-23, clave real de solo lectura):** ruta `/api/v1/trading/info/{env}/pnl` y saldo en `clientPortfolio.credit`, **en USD**. **No comprobado:** los campos de cada posición (`positions`): el 2026-11-24 la cuenta tenía 0 posiciones y 0 órdenes, así que solo se confirmó `accountCurrencyId` = 1 (USD) y las claves de `clientPortfolio`; y la conversión a EUR | `Endpoints.portfolio`, `EToroBroker.cash`, `EToroBroker.positions` | Ajustar el parseo; decidir la moneda con un ADR |
| 6 | Cancelación de una orden (`DELETE` sobre la ruta de órdenes). **Solo según la documentación, no probado:** `DELETE /api/v2/trading/execution/orders/{orderId}` (demo: `/execution/demo/orders/{orderId}`), `orderId` entero de eToro; un 200 solo confirma que la petición se aceptó, hay que comprobar el estado 7 o 9 con la consulta de la fila 4 | `EToroBroker.cancel` | Ajustar método y ruta |
| 7 | Si la API devuelve un identificador de orden que se pueda enlazar con el id interno cuando se pierde la respuesta | `BrokerRefStore`, `EToroBroker.lookup` | Sin enlace, una respuesta perdida detiene la operativa (comportamiento seguro) |
| 8 | Que verificar la cuenta y generar la clave demo no exijan depósito | cuenta | Si falla, revisar ADR-0006 |
| 9 | Que la API real esté habilitada para minoristas españoles | cuenta | Si falla, revisar ADR-0006 |
| 10 | Qué ETFs del universo salen como instrumentos reales (no CFD) y su id de instrumento. **Verificado en parte (2026-11-24, solo lectura):** búsqueda `GET /api/v2/market-data/instruments/search?query=<símbolo>` encuentra, con `type=ETF` y `exchangeId` 38 (símbolos `.DE`), EUNL.DE (id 15348, iShares Core MSCI World), SXR8.DE (id 3275, iShares Core S&P 500) e IS3N.DE (id 2914, iShares Core MSCI EM IMI). EUNK no apareció. **Real frente a CFD verificado** con `POST /api/v2/trading/info/eligibility` (solo consulta, clave real de solo lectura): los tres solo tienen configuración `settlementType` = `real` (largos), sin CFD, sin W-8BEN y con `allowOpenPosition` = true. Cotizan en tiempo real (`GET /api/v2/market-data/rates`, `quoteType` = `realtime`). **No comprobado:** la correspondencia con los ISIN (ningún endpoint leído trae ISIN; solo el nombre coincide) y la moneda de cotización | `config/universe.yml`, tabla `instrument` | Reducir el universo |
| 11 | Mínimo por orden (~10 $) y que acepte órdenes en euros. **Verificado en parte (2026-11-24, `POST /api/v2/trading/info/eligibility`, solo consulta):** `minPositionExposure` = 10 **USD** para EUNL.DE, SXR8.DE e IS3N.DE; admiten fracciones (`unitsQuantityType` = `fractional`, `allowedOrderQuantityType` = `all`). La documentación dice que `orderCurrency` y `currency` solo admiten USD, así que **no se pueden dar órdenes en euros**: el mínimo de `RiskConfig.minOrderEur` hay que convertirlo desde USD (decisión de moneda pendiente) | `RiskConfig.minOrderEur` | Ajustar universo y topes (ADR-0007) |
| 12 | Que las órdenes en real se comporten como en demo | primera orden real mínima | Ver ADR-0004 |
| 13 | Profundidad de las velas históricas. **Verificado (2026-11-24, solo lectura):** `GET /api/v1/market-data/instruments/{id}/history/candles/asc/OneDay/1000` devolvió 176 velas diarias para EUNL.DE (de 2026-03-18 a 2026-11-24), no 1000. La API no tiene parámetro de fechas, solo la cantidad. No se ha comprobado por qué son solo 176 | fase 1 | **Las velas de eToro no cubren los ≥10 años** del criterio de salida de la fase 1: usar fuente externa (ADR-0005) |

## Cómo se cierra

1. Poner la clave demo en `.env` (nunca en el repositorio ni en el chat).
2. Ejecutar un programa de prueba que lea catálogo y cartera, coloque y cierre una orden demo y guarde el resultado en PostgreSQL.
3. Por cada fila, marcar aquí «verificado» con la fecha, o corregir el código y los tests del servidor simulado.

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
| 3 | Ruta y respuesta del coste estimado (what-if) y que informe del tipo de liquidación | `Endpoints.estimate`, `EToroBroker.preflight` | Sin esta comprobación no se envían órdenes reales |
| 4 | Ruta y forma de la consulta de una orden y sus estados | `Endpoints.orderStatus`, `EToroBroker.state` | Ajustar el mapa de estados |
| 5 | Ruta y forma de cartera y efectivo. **Verificado en parte (2026-11-23, clave real de solo lectura):** ruta `/api/v1/trading/info/{env}/pnl` y saldo en `clientPortfolio.credit`, **en USD**. **No comprobado:** los campos de cada posición (`positions`) y la conversión a EUR | `Endpoints.portfolio`, `EToroBroker.cash`, `EToroBroker.positions` | Ajustar el parseo; decidir la moneda con un ADR |
| 6 | Cancelación de una orden (`DELETE` sobre la ruta de órdenes) | `EToroBroker.cancel` | Ajustar método y ruta |
| 7 | Si la API devuelve un identificador de orden que se pueda enlazar con el id interno cuando se pierde la respuesta | `BrokerRefStore`, `EToroBroker.lookup` | Sin enlace, una respuesta perdida detiene la operativa (comportamiento seguro) |
| 8 | Que verificar la cuenta y generar la clave demo no exijan depósito | cuenta | Si falla, revisar ADR-0006 |
| 9 | Que la API real esté habilitada para minoristas españoles | cuenta | Si falla, revisar ADR-0006 |
| 10 | Qué ETFs del universo salen como instrumentos reales (no CFD) y su id de instrumento | `config/universe.yml`, tabla `instrument` | Reducir el universo |
| 11 | Mínimo por orden (~10 $) y que acepte órdenes en euros | `RiskConfig.minOrderEur` | Ajustar universo y topes (ADR-0007) |
| 12 | Que las órdenes en real se comporten como en demo | primera orden real mínima | Ver ADR-0004 |
| 13 | Profundidad de las velas históricas | fase 1 | Usar fuente externa (ADR-0005) |

## Cómo se cierra

1. Poner la clave demo en `.env` (nunca en el repositorio ni en el chat).
2. Ejecutar un programa de prueba que lea catálogo y cartera, coloque y cierre una orden demo y guarde el resultado en PostgreSQL.
3. Por cada fila, marcar aquí «verificado» con la fecha, o corregir el código y los tests del servidor simulado.

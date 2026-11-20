# Resultado preliminar de la fase 2 (datos reales de Yahoo)

> Preliminar: una sola fuente, ISIN sin verificar contra eToro y calendario Xetra sin verificar. No cierra la fase 2.

## Fuente de datos (ADR-0005)

- **Stooq**: bloquea las descargas automáticas (la página exige JavaScript), así que no se pudo hacer la comparación de dos fuentes que pide la ADR.
- **Yahoo Finance** (`query1.finance.yahoo.com`, velas diarias pedidas por tramos de 4 años): tiene entre 12 y 17 años para los cuatro ETF probados. `range=max` degrada a datos mensuales; hay que pedir por tramos.
- Consecuencia: el criterio «diferencia < 0,5 % entre dos fuentes» no se ha podido comprobar. Queda abierto. Si se quiere cumplir, la opción de la ADR es pagar un mes de proveedor (EODHD, ~20 $) para contrastar.

## Universo de prueba (acumulación, EUR, Xetra)

| Ticker Yahoo | ISIN (de memoria, sin verificar) | Historia |
| --- | --- | --- |
| EUNL.DE | IE00B4L5Y983 (MSCI World) | desde 2009 |
| SXR8.DE | IE00B5BMR087 (S&P 500) | desde 2010 |
| EUNK.DE | IE00B4K48X80 (MSCI Europe) | desde 2009 |
| IS3N.DE | IE00BKM4GZ66 (MSCI EM IMI) | desde 2014 |

Descartados por historia insuficiente: VWCE (desde 2019, 7 años).
Los ISIN no se fijan en `config/universe.yml` hasta comprobar que eToro los lista.

## Calidad de datos

- El validador dio 128 «huecos» al principio. Eran cierres de mercado que el calendario no conoce (3 de octubre en varios años, lunes de Pentecostés, etc.). El ejecutor ya trata un día ausente en todos los instrumentos a la vez como cierre, y descarta incidencias anteriores a la ventana usada.
- Quedan 4 incidencias reales: 2 huecos sueltos (2014-08-11, 2021-05-19) y 2 saltos de precio el 2020-03-12 (caída real de ese día). Requieren revisión manual.
- Pendiente: contrastar el calendario Xetra con el oficial.

## Backtest (diseño 2014-06 a 2023-11; holdout desde 2023-11-19 sin abrir)

Benchmark (comprar y mantener MSCI World): CAGR 10,41 %, Sharpe 0,68, caída máxima 33,63 %.

| Estrategia | Ventanas ganadas al benchmark | Cumple (70 %) |
| --- | --- | --- |
| trend | 13 % (8 ventanas) | no |
| momentum | 13 % | no |
| vol-target | 38 % | no |
| swing | 25 % | no |

Ninguna familia supera al benchmark en el 70 % de las ventanas. Según la ADR-0014 eso significa abandonar esas familias con estos parámetros, o quedarse con un ETF global. El holdout no se ha abierto y no debe abrirse para rescatar una de ellas.
Estos resultados incluyen costes del modelo por defecto (5 bps de spread y 2 de deslizamiento, valores propios sin contrastar con eToro).

## Cargar los datos en PostgreSQL (reproducible)

Variables de entorno de Spring (o `application-*.yml`) para la primera carga con Yahoo:

```
TRADING_INGESTION_ENABLED=true
TRADING_INGESTION_SOURCE=yahoo
TRADING_INGESTION_ISINS=IE00B4L5Y983,IE00B5BMR087,IE00B4K48X80,IE00BKM4GZ66
TRADING_INGESTION_YAHOO_TICKERS=IE00B4L5Y983=EUNL.DE,IE00B5BMR087=SXR8.DE,IE00B4K48X80=EUNK.DE,IE00BKM4GZ66=IS3N.DE
TRADING_INGESTION_RUN_ON_STARTUP=true
```

`YahooBarSource` solo hace peticiones GET. La carga es idempotente (upsert), guarda el dato en bruto y el limpio por separado y registra las incidencias de calidad.

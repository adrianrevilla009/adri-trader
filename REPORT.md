# REPORT

1.
- Cambio: `EToroConfig.java` (ruta de cartera) y `EToroBroker.java` (`cash()` y `portfolio()`).
- Por qué: la ruta antigua no existía (404). La ruta real es `/api/v1/trading/info/{env}/pnl` y el saldo está en `clientPortfolio.credit`.
- Cómo comprobarlo: `mvn -pl trading-broker-etoro -am test -Dtest=EToroBrokerTest` → 16 tests, 0 fallos.

2.
- Cambio: `docs/fase0-verificacion-etoro.md`, fila 5.
- Por qué: la ruta de cartera ya está comprobada; faltaba dejarlo escrito, y también lo que sigue sin comprobar (posiciones y moneda USD).
- Cómo comprobarlo: abrir el archivo y leer la fila 5. Los tests del módulo eToro pasan (`mvn -pl trading-broker-etoro -am test`).

3.
- Cambio: `Dockerfile`, se añade `apk upgrade` a la imagen final.
- Por qué: apareció una vulnerabilidad nueva (libfontenc, HIGH) en la imagen base y el CI se ponía en rojo.
- Cómo comprobarlo: en el PR 51 el trabajo "Image" y el "CI gate" salen en verde.

4.
- Cambio: `.github/dependabot.yml`, Dependabot ignora las subidas de versión mayor de `grafana/tempo`.
- Por qué: Tempo 3 necesita migrar su configuración y la pila completa falla con él (PR 45), así que no debe volver a proponerse hasta migrarlo.
- Cómo comprobarlo: el PR pasa el "CI gate"; Dependabot deja de abrir PR de Tempo 3.

5.
- Cambio: `scripts/check-ops.sh`, versiones y digests de Prometheus y Alertmanager.
- Por qué: el script validaba las alertas con versiones viejas (v3.5.0 y v0.28.1) mientras el compose ya usa v3.16.0 y v0.35.0.
- Cómo comprobarlo: el trabajo "Operations config" del CI valida las alertas con las versiones nuevas y sale en verde.

6.
- Cambio: `docs/fase0-verificacion-etoro.md`, filas 5, 10 y 13.
- Por qué: se probó la API de eToro solo con lectura. Se encontraron 3 ETF (ids 15348, 3275 y 2914) y las velas diarias solo llegan a 176 días, así que no sirven para el histórico de 10 años.
- Cómo comprobarlo: leer esas filas; el programa de prueba solo hizo peticiones de lectura y no se envió ninguna orden.

7.
- Cambio: `docs/fase0-verificacion-etoro.md`, filas 3, 4, 6, 10 y 11.
- Por qué: se probó con solo consultas (sin órdenes) y se descubrió que los 3 ETF son activos reales (no CFD), que el mínimo es 10 USD y que la ruta del coste estimado del código es incorrecta.
- Cómo comprobarlo: leer esas filas. Ninguna orden se envió; el código del bot todavía no se ha corregido.

8.
- Cambio: `scripts/smoke-stack.sh`, la comprobación de trazas en Tempo ahora pide un rango de tiempo (última hora).
- Por qué: con Tempo 2.10 y 3.1 la búsqueda sin rango no encontraba trazas y el CI fallaba. Es una prueba: la causa no está confirmada.
- Cómo comprobarlo: el PR 58 (Tempo 2.10) con esta corrección debe pasar el trabajo "Whole stack". Con Tempo 2.8 pasa igual.

9.
- Cambio: `EToroBroker.preflight` y `EToroConfig` (rutas nuevas de elegibilidad y coste), y `EToroBrokerTest`.
- Por qué: la comprobación antigua usaba una ruta que no existe y habría rechazado todas las órdenes reales. Ahora se comprueba que el ETF es un activo real, que se puede abrir o cerrar, el mínimo y el coste estimado.
- Cómo comprobarlo: `mvn -pl trading-broker-etoro -am test -Dtest=EToroBrokerTest` → 25 tests, 0 fallos. Solo se probaron consultas contra eToro; no se envió ninguna orden.

10.
- Cambio: `.github/dependabot.yml` (Dependabot ignora todas las versiones nuevas de `grafana/tempo`) y un comentario en `scripts/smoke-stack.sh`.
- Por qué: Tempo 2.10 y 3.1 fallan la comprobación de trazas de la pila. Se probó dar un rango de tiempo a la búsqueda y no lo arregló; la causa no se conoce y hace falta Docker local para depurarla.
- Cómo comprobarlo: el PR pasa el "CI gate"; Dependabot deja de abrir PR de Tempo y se cierra el PR 58.

10.
- Cambio: `docs/fase0-verificacion-etoro.md`, sección nueva «Verificado con la clave demo».
- Por qué: se hizo la primera orden demo (compra de 10 USD de EUNL.DE y cierre) y se vio que el cuerpo de la orden y la ruta de cierre del código no coinciden con eToro.
- Cómo comprobarlo: leer esa sección. La cartera demo quedó vacía y con el saldo original (100000); no se tocó la cuenta real.

11.
- Cambio: `EToroConfig` (campo nuevo `maxOrderAmount`) y `EToroBroker.submit`, más 3 tests.
- Por qué: tope duro por orden; si el importe lo supera, se rechaza antes de enviar nada. Así las pruebas con eToro no pueden gastar de más.
- Cómo comprobarlo: `mvn -pl trading-broker-etoro -am test -Dtest=EToroBrokerTest` → 28 tests, 0 fallos.

12.
- Cambio: `EToroBroker` (compra, venta por cierre de posición, estado, cartera) y `EToroConfig` (rutas nuevas), con tests y una prueba opcional `EToroDemoLiveTest`.
- Por qué: el código antiguo enviaba un cuerpo que eToro rechaza y no sabía vender ni leer la cartera real; ahora usa lo comprobado en demo, con tope de 10 por orden.
- Cómo comprobarlo: `mvn -pl trading-broker-etoro -am test` → 40 tests, 0 fallos; con `ETORO_LIVE_DEMO=1` la prueba real compra 10 USD y los cierra en demo (cuenta demo limpia al final).

13.
- Cambio: `LiveConfig`, `EtoroProperties`, `LiveProperties` (trading-app), `CashCappedBroker` (trading-core), `application.yml`, `docker-compose.yml` y el ADR-0028.
- Por qué: el bot ya puede correr cada día (19:15) contra la cuenta demo de eToro, con tope de 10 por orden, solo modo DEMO y apagado por defecto.
- Cómo comprobarlo: `mvn -pl trading-app -am test -Dtest=LivePropertiesTest,CashCappedBrokerTest` → 11 tests, 0 fallos; el CI arranca la aplicación con el ciclo encendido (`LiveContextTest`).

14.
- Cambio: `docs/fase0-verificacion-etoro.md`, sección «Cancelación e instrumentos».
- Por qué: se probó en demo cancelar una orden pendiente (funciona con la ruta del código) y se comprobó que eToro tiene 3 de los 4 ETF del universo; EUNK.DE no existe allí.
- Cómo comprobarlo: leer la sección. Solo se hizo una orden pendiente de 10 USD en demo y se canceló; la cuenta demo quedó limpia.

15.
- Cambio: `docs/adr/0029-yahoo-fuente-historico.md` y el índice de ADR.
- Por qué: faltaba cerrar la elección de la fuente de datos históricos. Se comparó Yahoo con las velas de eToro del último año: diferencia media entre 0,05 % y 0,14 %.
- Cómo comprobarlo: leer el ADR. Solo se hicieron consultas de lectura; no se envió ninguna orden.

16.
- Cambio: `LiveProperties`, `LiveConfig` y `DataConfig`: opción `trading.live.run-on-startup-day` y orden de arranque (primero la ingesta, luego el día).
- Por qué: poder probar el ciclo completo de un día concreto contra la demo sin esperar a las 19:15.
- Cómo comprobarlo: `mvn -pl trading-app -am test -Dtest=LivePropertiesTest` → 8 tests, 0 fallos; con la opción puesta, el log muestra `Live day ... TRADED`.

17.
- Cambio: `EToroBroker.lookup`: el precio medio de una compra ahora es importe / unidades (en la moneda de la cuenta).
- Por qué: la prueba completa con la demo se paraba en la reconciliación (esperadas 0,07843 unidades, eToro 0,071552) porque el importe estaba en USD y el precio en EUR.
- Cómo comprobarlo: `mvn -pl trading-broker-etoro -am test -Dtest=EToroBrokerTest` → 40 tests, 0 fallos; el ciclo completo en demo reconcilia sin diferencias.

18.
- Cambio: `EtoroProperties` (campo `minOrderAmount`), `LiveConfig` (el riesgo usa ese mínimo) y `application.yml`.
- Por qué: en la prueba con la demo el bot envió una compra de 3,31; eToro la aceptó y la rechazó después (mínimo 10 USD). Ahora el riesgo descarta las órdenes menores de 10 antes de enviarlas.
- Cómo comprobarlo: `mvn -pl trading-app -am test -Dtest=LivePropertiesTest` → 9 tests, 0 fallos; en la demo, un día sin orden pequeña: `sent 0` y la orden rechazada anterior queda `REJECTED`.

19.
- Cambio: `docs/runbook.md` (sección nueva del ciclo diario en demo) y `CLAUDE.md` (lo que sigue sin verificar).
- Por qué: explicar cómo encender, probar, vigilar y apagar el ciclo demo, y dejar claro qué está ya comprobado en eToro y qué no.
- Cómo comprobarlo: leer la sección «Ciclo diario en la cuenta demo de eToro» del runbook.

20.
- Cambio: `docs/moneda-y-conversion.md` (documento nuevo).
- Por qué: dejar por escrito cómo afecta el cambio USD/EUR de eToro al dinero real, marcando lo que no está comprobado.
- Cómo comprobarlo: leer el documento; no cambia código, así que no hay tests nuevos.

21.
- Cambio: `docs/adr/0030-agentes-dinamicos-api-ui.md` y su fila en `docs/adr/README.md`.
- Por qué: dejar decidido que los agentes se crean y paran desde una API y una UI, con la configuración en base de datos.
- Cómo comprobarlo: leer la ADR; no cambia código, así que no hay tests nuevos.

22.
- Cambio: `trading-core`, paquete `domain`: `AgentConfig`, `CapitalPolicy`, `EscalationStep`, `TradingMode`, `AgentRunState` (clases nuevas) y sus tests.
- Por qué: es el modelo del agente configurable (estrategia, parámetros, capital, pasos); rechaza capital bajo el mínimo y el modo PROD.
- Cómo comprobarlo: `mvn -pl trading-core test` → 55 tests, 0 fallos.

23.
- Cambio: `V7__agent.sql`, `AgentStore` (puerto) y `JdbcAgentStore` (adaptador), más `AgentConfig` que ignora ceros de más (200 = 200.00).
- Por qué: guardar los agentes y cada cambio de su configuración (quién, cuándo, valor anterior y nuevo) en la base de datos.
- Cómo comprobarlo: `mvn -pl trading-adapter-persistence -am test` → 10 tests nuevos de base de datos y 21 del dominio, 0 fallos.

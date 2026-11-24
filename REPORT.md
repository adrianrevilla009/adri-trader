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

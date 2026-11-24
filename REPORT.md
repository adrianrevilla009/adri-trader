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
- Cambio: `ops/tempo/tempo.yml`, se quita el bloque `compactor` (Tempo 3 ya no lo acepta).
- Por qué: con Tempo 3 el contenedor no arrancaba y no se guardaban trazas. La retención de 14 días ya es el valor por defecto, pero no está comprobado en una instancia real.
- Cómo comprobarlo: en el PR 45 el trabajo "Whole stack" y el "CI gate" salen en verde.

# Bot de trading en Java: validación y plan técnico por fases

Nov 19, 2026 · @Adrian

## Validación del plan

El orden datos, backtest, paper trading y dinero real es correcto y se mantiene, con seis correcciones que lo hacen más realista para un proyecto personal en Java.

**Lo que se mantiene**

- Cada fase tiene un criterio de salida medible y no se avanza sin cumplirlo.
- El módulo de riesgo es independiente de las estrategias y tiene la última palabra sobre cualquier orden.
- La arena de agentes compite primero en simulación y solo los supervivientes pasan a dinero real.

**Lo que se corrige**

1. **Una sola interfaz de estrategia para backtest, simulación y real.** Una estrategia recibe datos y devuelve decisiones; no sabe en qué modo corre. Si el código difiere entre modos, el backtest no valida nada. Esto se diseña en la fase 2, no en la 4.
2. **La arena usa un simulador propio, no solo el demo del broker.** Las cuentas paper de un broker suelen rellenar órdenes de forma optimista y sin impacto de mercado. El demo de eToro sirve para probar la conexión y las órdenes; la comparación entre agentes se hace con un simulador propio alimentado con datos reales, comisiones y slippage configurables.
3. **El libro de operaciones (ledger) y la fiscalidad entran en la fase 1.** Cada operación, comisión, dividendo y divisa se registra desde el primer día; reconstruirlo después es doloroso.
4. **Criterio estadístico explícito en la arena.** Con varios agentes, el mejor de un trimestre puede ser suerte. Se fija de antemano un mínimo de meses y operaciones y se compara contra el benchmark con métricas ajustadas por riesgo.
5. **La fase 6 se reordena.** Primero régimen de mercado y datos macro, luego el meta-agente, después noticias con LLM como filtro y, al final y solo si hay base sólida, ML sobre precios.
6. **El dinero real de 10 € por agente solo es viable sin comisión por orden.** Con Interactive Brokers los mínimos por orden se comían el capital; con un broker de 0 € de comisión en ETFs y órdenes por importe (eToro, ver Decisiones cerradas) los agentes corren en simulación con 100 € y un solo agente pasa a real con esos 100 € en la fase 5.

**Supuestos de partida**

- Horizonte diario o semanal: el bot se ejecuta una vez al día tras el cierre de los mercados europeos.
- Solo posiciones largas, sin apalancamiento ni cortos al principio.
- Un desarrollador a tiempo parcial, con Java como lenguaje principal.
- Universo inicial (v1): solo ETFs UCITS de acumulación cotizados en euros en Xetra; las acciones individuales quedan para la v2.
- Capital inicial de 100 € simulados y, solo tras validar en demo, 100 € reales en eToro, un broker sin comisión en ETFs reales; el plan se dimensiona para validar el sistema, no para obtener rentabilidad apreciable.

## Arquitectura y stack

La arquitectura es una cadena única (datos, estrategia, riesgo, broker) que sirve igual para backtest, arena y real, con PostgreSQL como memoria común.

&#91;embedded content: arquitectura · cadena de decisión, base de datos y módulos de soporte\]

Las estrategias solo conocen la interfaz `Broker`: que detrás esté el simulador o un broker real se decide por configuración, no por código.

**Decisiones técnicas**

| Decisión | Propuesta | Motivo |
| --- | --- | --- |
| Lenguaje | Java 21 | Es el mínimo que exige ta4j 0.22 |
| Framework | Spring Boot | Inyección de dependencias, planificador de tareas y configuración por entorno sin escribir infraestructura |
| Base de datos | PostgreSQL con migraciones Flyway | Datos diarios de pocos instrumentos; no hace falta una base de series temporales |
| Acceso a datos | JDBC con `JdbcTemplate` o jOOQ | SQL explícito y fácil de auditar para el libro de operaciones |
| Dinero y cantidades | `BigDecimal` | Evita errores de coma flotante en precios, comisiones y saldos; `double` solo en cálculo estadístico |
| Indicadores | ta4j | Medias, RSI, volatilidad y métricas ya implementadas |
| Concurrencia | Un solo hilo de decisión por job y consulta periódica del estado de las órdenes en el broker | La API REST se consulta, no avisa por eventos; un solo hilo evita condiciones de carrera al reconciliar |
| Pruebas | JUnit 5, AssertJ y Testcontainers | Pruebas con PostgreSQL real y un broker falso para fallos |
| Observabilidad | Logs estructurados con Logback y alertas por la API de bots de Telegram | Suficiente para un proyecto personal |
| Despliegue | Docker Compose con la aplicación y PostgreSQL en un servidor Linux pequeño (VPS o mini PC) | Un único comando para levantar todo |
| Configuración | YAML por entorno (`dev`, `paper`, `live`) y secretos en variables de entorno | Nada sensible en el repositorio |

**Módulos del proyecto (Maven multimódulo)**

- `trading-core`: modelo de dominio e interfaces (`Strategy`, `Broker`, `RiskRule`, `MarketSnapshot`).
- `trading-data`: ingesta, calendario, calidad y tipos de cambio.
- `trading-backtest`: motor de simulación, modelo de costes, validación e informes.
- `trading-strategies`: las estrategias y sus parámetros.
- `trading-risk`: reglas de riesgo y kill switch.
- `trading-broker-etoro` y `trading-broker-sim`: las dos primeras implementaciones de `Broker`; `trading-broker-ib` queda como adaptador opcional si el capital algún día justifica sus comisiones.
- `trading-arena`: orquestador de agentes, agentes de control y métricas.
- `trading-tax`: `TaxLedger` y exportaciones fiscales.
- `trading-app`: aplicación Spring Boot, jobs programados, alertas y panel.

## Fase 0: fundamentos

La fase 0 deja fijadas las reglas del juego (broker, universo de activos, benchmark y límites de pérdida) y un esqueleto de proyecto que compila y pasa tests; no se escribe todavía ninguna lógica de trading.

**Tareas técnicas**

1. **Cuenta de eToro verificada (sin depósito) y claves de demo.** La [API pública](https://api-portal.etoro.com/) exige cuenta verificada (KYC: documento de identidad y justificante de domicilio). Las claves se crean en Ajustes → Trading → Gestión de claves de API, una por entorno (demo y real). No comprobado: que verificar la cuenta y generar la clave de demo no exijan depósito, y que la API con dinero real esté habilitada para cuentas minoristas españolas; se confirma al abrir la cuenta.
2. **Entorno de eToro.** API REST con las cabeceras `x-api-key`, `x-user-key` y `x-request-id` (un UUID por petición). Las lecturas van a `https://public-api.etoro.com/api/v1/`; las órdenes demo a `POST /api/v2/trading/execution/demo/orders` y las reales a `/api/v2/trading/execution/orders`. No hay Gateway ni sesión interactiva. Dos cautelas: la API está en beta y eToro puede conceder, denegar o revocar el acceso en cualquier momento; y los límites son de 60 lecturas y 20 escrituras por minuto y clave (con 429, espera según Retry-After).
3. **Universo inicial de 10 a 15 ETFs UCITS de acumulación en euros** (renta variable global, EEUU, Europa, emergentes, bonos de distinto plazo, oro). Los ETFs de EEUU como SPY o VOO [están bloqueados](https://ibkb.interactivebrokers.com/es/node/2993) para minoristas del EEE por la normativa PRIIPs, así que se usan sus gemelos irlandeses o luxemburgueses. La lista final se limita a los que aparezcan en el catálogo de eToro como instrumentos reales, no CFD (no comprobado cuáles; eToro añadió unos 250 UCITS en enero de 2026, pero tiene menos oferta que otros brokers europeos).
4. **Identificar cada ETF por ISIN, no por ticker.** Un mismo UCITS cotiza en varias bolsas y divisas, y cada broker usa su propio identificador. Se crea una tabla `instrument` con ISIN, bolsa, divisa, identificador de instrumento de eToro y tipo (acumulación o distribución).
5. **Benchmark.** Un ETF UCITS global de acumulación (MSCI World o FTSE All-World) contra el que se mide todo.
6. **Reglas de riesgo escritas en configuración**, no en el código. Valores iniciales propuestos, a ajustar: máximo 25 % del capital por activo, pérdida máxima diaria del 2 %, y un agente se apaga solo si su caída desde máximos supera el 15 %.
7. **Esqueleto del proyecto.** Java 21 (mínimo de ta4j 0.22), Maven multimódulo, Git, integración continua con GitHub Actions, PostgreSQL en Docker con migraciones Flyway, logging con SLF4J y Logback, y configuración en YAML por entorno (dev, paper, live).

**Criterio de salida:** cuenta demo operativa y un programa Java de prueba que se conecta a la API de eToro en demo, coloca y cierra una orden de prueba, lee el catálogo de instrumentos y la cartera demo y los guarda en la base de datos, universo y reglas de riesgo escritos y versionados.

## Fase 1: datos

La fase 1 construye la tubería que descarga, limpia y almacena precios y operaciones con un historial reproducible; si los datos fallan, ningún backtest vale. A escala diaria el volumen es pequeño: 20 ETFs durante 20 años son unas 100.000 filas, así que PostgreSQL a secas basta.

**Fuentes de datos**

- **eToro por API** para catálogo, posiciones, órdenes y velas históricas (profundidad de las velas no comprobada). El backtest necesita 10 años o más: si las velas de eToro no llegan, se descarga una sola vez el histórico de una fuente externa (EODHD durante un mes de pago, Twelve Data o Stooq/Yahoo) y se guarda en PostgreSQL.
- **Un proveedor externo** para historia larga (10 años o más) y para cruzar dos fuentes entre sí. En esta fase se comparan opciones gratuitas (CSV públicos como Stooq) y de pago (Tiingo, EODHD u otras) según cobertura de ETFs UCITS europeos; es una decisión abierta.
- **Tipos de cambio** del BCE, solo para la v2: la v1 opera únicamente ETFs cotizados en euros, así que no hay conversión de divisa ni coste de cambio.

**Modelo de datos inicial**

| Tabla | Contenido | Clave |
| --- | --- | --- |
| `instrument` | ISIN, bolsa, divisa, identificador de instrumento de eToro, tipo (acumulación o distribución) | ISIN + bolsa |
| `bar_daily_raw` | Apertura, máximo, mínimo, cierre y volumen tal como llegan de cada fuente | instrumento + fecha + fuente |
| `bar_daily` | Serie limpia y ajustada por dividendos y splits que usa el backtest | instrumento + fecha |
| `fx_rate` | Cambio diario a euros | divisa + fecha |
| `corporate_action` | Dividendos, splits y cambios de ticker | instrumento + fecha + tipo |
| `trading_calendar` | Días de negociación y festivos por bolsa | bolsa + fecha |
| `order`, `fill`, `cash_ledger` | Libro de operaciones: órdenes, ejecuciones, comisiones, dividendos y movimientos de efectivo | id de orden, id de ejecución |
| `data_quality_issue` | Anomalías detectadas y su estado | id |

**Reglas de calidad**

1. **Raw y limpio separados.** El dato crudo nunca se modifica; la serie limpia se regenera desde él.
2. **Validaciones automáticas** en cada ingesta: huecos de fechas frente al calendario, precios cero o negativos, saltos superiores al 10 % sin evento corporativo, volumen cero, duplicados y discrepancias entre fuentes.
3. **Sin sesgo de futuro.** Cada dato guarda la fecha desde la que estaba disponible, para que el backtest no use información que aún no existía.
4. **Sin sesgo de supervivencia.** Los activos que dejan de cotizar se conservan; con ETFs pesa poco, y por eso las acciones sueltas quedan para la v2: elegir hoy las acciones más grandes ya sesga el pasado a favor de las que sobrevivieron.
5. **Datos versionados.** Cada backtest registra una versión o hash del conjunto de datos usado, para poder repetirlo exactamente.

**Ingesta diaria**

Un job programado (`@Scheduled` de Spring o Quartz) se ejecuta tras el cierre de las bolsas europeas, es idempotente (hace *upsert*, no duplica), reintenta ante fallos y avisa por Telegram si algo no cuadra. El libro de operaciones queda creado ya en esta fase, aunque esté vacío, porque la contabilidad y los impuestos dependen de él.

**Criterio de salida:** histórico limpio de todo el universo (10 años o lo máximo disponible), ingesta diaria automática durante dos semanas sin intervención, informe de calidad sin errores críticos y libro de operaciones funcionando.

## Fase 2: backtesting y estrategias

La fase 2 construye un motor que simula estrategias sobre los datos históricos con costes realistas y una validación que desconfía de los buenos resultados. Es la fase donde más se decide si el proyecto tiene sentido.

**Decisión de diseño: ta4j para señales, motor propio para la cartera**

[ta4j](https://github.com/ta4j/ta4j) (0.22.x, Java 21 o superior) aporta indicadores, reglas y métricas, y su [modelo de registro de operaciones](https://ta4j.github.io/ta4j-wiki/Home.html) cubre backtest, paper y real. Pero una estrategia de ta4j opera sobre una sola serie de precios, y las rotaciones entre ETFs necesitan decidir sobre varias a la vez. Por eso se usa ta4j para los indicadores y se escribe un motor propio a nivel de cartera.

**Contrato de estrategia (la pieza clave)**

Cada estrategia implementa una única interfaz, igual en backtest, simulación y real:

```java
public interface Strategy {
    String id();
    // Recibe solo datos hasta el cierre de hoy y el estado de la cartera;
    // devuelve los pesos objetivo por instrumento (suman ≤ 100 %).
    TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state);
}
```

El motor ejecuta el bucle diario: construye el `MarketSnapshot` con datos hasta el cierre del día t, llama a la estrategia, pasa la propuesta por el módulo de riesgo y simula la ejecución al precio de apertura del día t+1 con un slippage adicional prudente, porque en real se opera poco después de la apertura (con órdenes a mercado si la API real solo admite esas, ver fase 3) y el agente sombra de la fase 5 medirá la diferencia. Esa separación de un día es lo que impide usar información del futuro.

**Modelo de costes (parámetros configurables)**

- Comisión por orden: 0 € en ETFs reales de eToro. El coste de divisa (0,75 % en activos en USD desde una cuenta en euros) no aplica a ETFs en euros, y la v1 opera solo ETFs UCITS en euros; que eToro acepte órdenes en euros no está comprobado. El coste real es el spread, que se mide con el endpoint de coste estimado (what-if). Las acciones sueltas (1–2 $ por lado en España) quedan fuera de la v1, y el mínimo por orden es de unos 10 $. Cifras en parte de terceros, a confirmar en la tarifa oficial al abrir la cuenta.
- Spread: medio spread en puntos básicos por ETF, mayor en los menos líquidos; sin comisión, pasa a ser el coste principal.
- Slippage: desviación adicional entre el precio de decisión y el de ejecución.
- Coste de divisa en ETFs que no cotizan en euros.
- Módulo opcional de IRPF para ver la rentabilidad neta de impuestos.

**Estrategias iniciales (la arena de la fase 4 parte de aquí)**

| Estrategia | Frecuencia | Lógica | Parámetros principales |
| --- | --- | --- | --- |
| Tendencia | Diaria o semanal | Invertido si el cierre está sobre su media de 200 días; si no, bonos o efectivo. Banda de histeresis para evitar entradas y salidas repetidas | Ventana de la media, ancho de banda |
| Momentum con rotación | Mensual | Ordena ETFs por rentabilidad de 3 a 12 meses, compra los N mejores y pasa a efectivo si ninguno sube | Ventanas, N |
| Volatilidad objetivo | Mensual | Pesos inversamente proporcionales a la volatilidad reciente | Ventana de volatilidad, tope por activo |
| Swing en ETFs | Días a semanas | Compra de retrocesos cortos dentro de tendencia alcista (por ejemplo RSI de 2 días bajo con precio sobre media de 200) y salida por objetivo o tiempo | Umbrales, tiempo máximo, stop |

**Validación (donde se evita engañarse)**

1. **Separación en muestras.** Se diseña con una parte del histórico y se prueba en otra que no se ha mirado.
2. **Walk-forward.** Se reoptimiza en ventanas móviles y se mide solo en el tramo siguiente, repetido a lo largo del histórico.
3. **Sensibilidad de parámetros.** Si mover un parámetro un 20 % destruye el resultado, la estrategia es frágil y se descarta.
4. **Registro de intentos.** Se anota cuántas variantes se han probado; cuantas más, más probable es que la mejor sea suerte, y el umbral de aceptación sube.
5. **Remuestreo de operaciones** (bootstrap o Monte Carlo) para ver el rango plausible de resultados y de caída máxima.
6. **Tests del propio motor.** Series sintéticas con respuesta conocida y un test de no anticipación: cambiar datos futuros no puede alterar decisiones pasadas.

**Métricas que se calculan siempre, netas de costes**

Rentabilidad anualizada (CAGR), volatilidad, ratio de Sharpe y de Sortino, caída máxima, ratio de Calmar (CAGR dividido por caída máxima), rotación de cartera, porcentaje de tiempo invertido, número de operaciones, y frente al benchmark: alfa, beta y *tracking error*.

**Trazabilidad:** cada ejecución guarda un identificador con la configuración, la versión de los datos, el commit de Git y el informe (HTML y CSV), de modo que cualquier resultado se puede reproducir.

**Criterio de salida (umbrales propuestos, a confirmar):** al menos una estrategia que, neta de costes, supere al benchmark en Sharpe o Calmar en el 70 % o más de las ventanas del walk-forward, que aguante variaciones de parámetros del 20 % sin cambiar de signo, y con un mínimo de 30 operaciones si es de swing. Si ninguna lo cumple, el resultado válido es no seguir con esa familia de ideas.

## Fase 3: riesgo y ejecución

La fase 3 construye las dos piezas que protegen el capital y tocan dinero: un gestor de riesgo que puede recortar o vetar cualquier orden, y una capa de ejecución que habla con el broker (eToro) sin duplicar ni perder órdenes. Aquí un fallo de software cuesta dinero, por eso es la fase con más tests de fallos.

**Gestor de riesgo (`RiskManager`)**

Recibe la cartera objetivo de una estrategia, la compara con el estado real y devuelve órdenes aprobadas, recortadas o rechazadas. No depende de ninguna estrategia concreta.

| Regla | Qué comprueba | Acción |
| --- | --- | --- |
| Peso máximo por activo | Que ningún instrumento supere el tope de la configuración | Recorta la orden |
| Exposición total | Capital invertido frente a capital del agente | Recorta |
| Pérdida diaria máxima | Resultado del día frente al límite | Bloquea nuevas compras hasta el día siguiente |
| Caída desde máximos del agente | Drawdown acumulado frente al límite | Apaga el agente y avisa |
| Lista blanca de instrumentos | Que el ISIN esté en el universo aprobado | Rechaza |
| Liquidez mínima | Volumen medio frente al tamaño de la orden | Recorta o rechaza |
| Precio razonable (*fat finger*) | Que el precio límite no se desvíe más de un porcentaje del último precio | Rechaza |
| Orden duplicada | Misma intención ya enviada hoy | Rechaza |
| Horario de mercado | Que la bolsa esté abierta y no sea subasta | Aplaza |

**Kill switch**

Un interruptor manual que cancela las órdenes abiertas y bloquea cualquier orden nueva. Se activa desde un comando de Telegram, un indicador en base de datos o un endpoint protegido, y está probado antes de poner un euro real. No liquida posiciones por sí solo: eso lo decide la persona.

**Capa de ejecución**

- **Interfaz `Broker`** con operaciones para enviar y cancelar órdenes y consultar posiciones, efectivo y estado de órdenes. Dos implementaciones: `SimulatedBroker` (fase 4) y `EToroBroker`, que llama a la [API pública de eToro](https://api-portal.etoro.com/) con el cliente HTTP estándar de Java; no hace falta un adaptador en Python. `IbBroker` queda como opción futura si el capital llega a justificar sus comisiones.
- **API REST beta con límites.** Órdenes por importe (`amount`), unidades o contratos (solo uno de los tres), siempre con `leverage` 1 y `settlementType` fijado a `real` para que nunca se ejecute como CFD; antes de cada orden real se consulta el coste estimado (what-if) y se aborta si no sale como activo real. Hay límite de 20 escrituras por minuto. En la documentación consultada no figuran claves de idempotencia en las órdenes (solo un `x-request-id` por petición), así que la orden se guarda en base de datos antes de enviarla y, si no llega respuesta, se consultan órdenes y posiciones antes de reintentar.
- **Máquina de estados de orden**, guardada en base de datos antes de enviar: `CREATED`, `SUBMITTED`, `PARTIALLY_FILLED`, `FILLED`, `CANCELLED`, `REJECTED`. Cada orden lleva una clave de idempotencia propia.
- **Tipo de orden.** El plan sigue siendo operar poco después de la apertura y evitar las subastas. La API admite órdenes a mercado y limitadas. No comprobado: que las órdenes en real funcionen igual que en demo para una cuenta española; se verifica en la fase 0 en demo y, si más adelante se financia, con una orden real de importe mínimo. El agente sombra mide el slippage. Para cumplir las condiciones de eToro no se envían órdenes de compra y venta de ETFs similares en momentos próximos: las rotaciones escalonan primero la venta y después la compra.
- **Reintentos.** Los datos se reintentan con espera creciente; una orden nunca se reenvía a ciegas, primero se consulta su estado.

**Reconciliación**

Al arrancar y después de operar, el bot compara posiciones y efectivo del broker con su libro interno. Si la diferencia supera la tolerancia, detiene la operativa y avisa; no "arregla" nada solo.

**Operación y seguridad**

- Primeras pruebas solo con endpoints de lectura, después demo y, solo si se decide financiar la cuenta, órdenes reales de importe mínimo. Los términos de eToro para herramientas autónomas exigen limitación propia de ritmo, tope de frecuencia de órdenes, reintentos con espera, anulación manual, límites de riesgo y registro de auditoría de las decisiones: el bot los implementa como requisito, no como mejora.
- Modo *dry-run* que calcula órdenes y las registra sin enviarlas.
- Credenciales fuera del repositorio (variables de entorno o gestor de secretos) y conexión a la API restringida a la máquina local.
- Alertas por Telegram de errores, desconexiones, discrepancias y resumen diario, y un latido (*heartbeat*) externo que avise si el bot deja de reportar durante 24 horas.
- Despliegue en un servidor Linux pequeño (VPS o mini PC) con Docker Compose. Sin Gateway ni doble factor periódico, el bot solo necesita salida HTTPS; las claves de API (una por entorno) van como secreto.

**Tests de fallos:** un broker falso que simula rechazos, ejecuciones parciales, desconexión a mitad de orden y respuestas tardías, y comprueba que el sistema nunca duplica una orden ni pierde el rastro de ninguna.

**Criterio de salida:** cuatro semanas en el demo de eToro sin intervención manual, reconciliación sin diferencias sin explicar, tests de fallos en verde y kill switch probado en un ensayo real.

## Fase 4: arena de agentes en paper trading

La fase 4 pone a competir varias estrategias a la vez con datos reales y dinero simulado, y sirve sobre todo para comprobar que lo que se ve en vivo coincide con lo que prometió el backtest. Es una fase de espera más que de programación: meses, no semanas.

**Piezas**

- **Agente** = una `Strategy` con sus parámetros, una cartera virtual propia, un capital y sus límites de riesgo. Es una fila de configuración más su estado en base de datos.
- **`SimulatedBroker`**: ejecuta las órdenes a los precios reales del día siguiente con el mismo modelo de costes de la fase 2. Implementa la misma interfaz `Broker` que `EToroBroker`.
- **Orquestador de la arena**: un único job diario que, con el mismo conjunto de datos para todos, hace decidir a cada agente, pasa sus órdenes por el gestor de riesgo, las simula y guarda el resultado. Cada agente corre aislado: si uno falla, los demás siguen.
- **Capital simulado igual al real.** Sin comisión por orden, cada agente corre con 100 €, el mismo importe que irá a real si se decide financiar. La comparación incluye spread y slippage, que ahora son el coste dominante, y el mínimo por orden de eToro (unos 10 $, no comprobado).

**Agentes de control**

Además de las estrategias, la arena incluye:

- Un agente que compra el benchmark y lo mantiene.
- Varios agentes aleatorios con la misma rotación y exposición que cada estrategia real, con semilla fija. Su rendimiento dibuja cuánto se puede ganar solo por suerte: una estrategia que no los supera con claridad no ha demostrado nada.

**Orden del job diario**

1. Ingesta de datos y validación de calidad.
2. Ejecución simulada de las órdenes decididas el día anterior.
3. Valoración de las carteras al cierre.
4. Decisión de cada agente con datos hasta hoy.
5. Gestor de riesgo y registro de las órdenes pendientes.
6. Informe diario y alertas.

**Ciclo de vida de un agente**

`CANDIDATO` (supera la fase 2) pasa a `ARENA`, después a `FINALISTA` (cumple los criterios de abajo), a `REAL_PEQUEÑO` (fase 5) y, cuando se degrada, a `RETIRADO`.

**Comprobación clave: vivo frente a backtest**

Cada semana se ejecuta el backtest de cada agente sobre el mismo periodo que ya ha corrido en vivo y se compara la curva de patrimonio. Una divergencia grande indica un fallo del motor, anticipación de datos futuros o costes mal modelados, y se corrige antes de confiar en ningún resultado.

**Panel de seguimiento**

Curva de patrimonio de cada agente frente al benchmark, caída desde máximos, Sharpe móvil, costes pagados, rotación, exposición y lista de operaciones. En la v1 es un informe HTML estático generado cada noche y enviado por Telegram; Grafana sobre PostgreSQL queda como mejora opcional.

**Criterios para pasar a finalista (umbrales propuestos, fijados antes de empezar)**

1. Al menos 3 meses de arena para detectar fallos técnicos y 6 meses o más para juzgar resultados.
2. Diferencia con su backtest dentro de una tolerancia acordada.
3. Sharpe o Calmar neto igual o superior al del benchmark.
4. Caída máxima dentro del límite de riesgo.
5. Resultado por encima del percentil 90 de los agentes aleatorios.

Una nota de honestidad: una estrategia mensual toma solo seis decisiones en seis meses, así que la arena detecta errores y desviaciones, pero rara vez demuestra por sí sola que hay ventaja. La prueba principal sigue siendo el backtest largo de la fase 2.

**Criterio de salida:** al menos un finalista que cumpla los cinco criterios, o la conclusión documentada de que ninguno lo hace y se vuelve a la fase 2.

## Fase 5: dinero real escalonado, impuestos y obligaciones

La fase 5 conecta solo a los finalistas de la arena con la cuenta real, con capital pequeño y límites más estrictos que en simulación, y añade el cálculo fiscal que en España condiciona la rentabilidad real. Esta sección no es asesoramiento fiscal; conviene validarla con un asesor.

**Puesta en marcha técnica**

1. **Interruptor por agente.** Cada agente se activa en real por configuración, con su propio capital asignado y sin posibilidad de tocar el de otro.
2. **Límites reducidos al inicio.** Por ejemplo, la mitad del tamaño objetivo y un tope de pérdida diaria menor que en la arena (valores propuestos).
3. **Cuenta sin margen**, si el broker lo permite, para que un error no pueda generar apalancamiento.
4. **Agente sombra.** Cada agente real tiene un gemelo en la arena con la misma estrategia. Se comparan las ejecuciones reales con las simuladas y se recalibran comisiones y slippage del modelo.
5. **Escalado por tramos.** Solo se aumenta el capital tras un periodo sin incidentes (por ejemplo 3 meses) y con diferencia aceptable frente al gemelo simulado.
6. **Manual de operación (*runbook*).** Qué hacer si la API del broker no responde, si falla la reconciliación o si hay que activar el kill switch; copias de seguridad diarias de PostgreSQL y restauración probada.

**Impuestos: lo que el bot debe saber**

- **Tarifa.** Las ganancias por vender ETFs o acciones tributan en la base del ahorro: [19 % hasta 6.000 €, 21 % hasta 50.000 € y 23 % hasta 200.000 €](https://www.billeo.es/blog/ganancias-patrimoniales-venta-acciones-2026), con tipos más altos por encima.
- **Cada venta con beneficio tributa.** Los ETFs [tributan como acciones y no existe traspaso fiscal](https://www.ocu.org/inversiones/fiscalidad-y-derechos/impuestos/articulos/2026/02/peaje-fiscal-de-sus-inversiones-y-ahorros) como en los fondos de inversión. Una estrategia que rota mucho paga impuestos en cada giro, y eso debe verse en las métricas netas.
- **Regla de los dos meses.** Si se vende con pérdida y se recompran valores homogéneos dentro de los dos meses anteriores o posteriores, [la pérdida no se puede computar en ese momento](https://internet.cnmv.es/DocPortal/Publicaciones/Guias/GuiaFiscalidadAcciones2026.pdf). Un bot de rotación recompra a menudo el mismo ETF, así que el módulo fiscal debe marcar esas pérdidas.
- **Criterio FIFO.** Con varias compras del mismo valor, [se entienden vendidas primero las más antiguas](https://hacecuentas.com/es/impuestos/ahorro-e-inversiones).

**Módulo fiscal (`TaxLedger`)**

Calcula por ISIN las ganancias y pérdidas con FIFO, convierte a euros al tipo de cambio de cada fecha, marca las pérdidas afectadas por la regla de los dos meses, y exporta un informe anual en CSV con ventas, dividendos y retenciones. Se alimenta del libro de operaciones creado en la fase 1. Conviene guardar también los informes del broker (eToro permite exportar el estado de cuenta en CSV; no comprobado desde la API). En cambio IB [no entrega documentación específica para la renta](https://www.elclubdeinversion.com/ufaq/que-obligaciones-fiscales-tengo-si-opero-con-interactive-brokers-desde-espana/).

**Modelo 720**

Quien tiene valores custodiados en un broker extranjero como eToro (entidad europea) [debe presentar el modelo 720 si supera los 50.000 € en alguna categoría](https://www.allinvestview.com/es/modelo-720-broker-extranjero/), y el broker no informa de ello a Hacienda por el contribuyente, [como señala Rankia](https://www.rankia.com/blog/impuestos/1623888-modelo-720-que-como-rellenarlo?page=15). El módulo fiscal debe avisar cuando el valor a 31 de diciembre se acerque a ese umbral.

**Límite legal**

Operar con capital propio con un bot es una actividad personal; gestionar dinero de terceros sería una actividad regulada. Si el proyecto evolucionara en esa dirección, habría que revisarlo con un abogado antes.

**Criterio de salida:** al menos 3 meses con dinero real sin incidentes, tracking aceptable frente al gemelo simulado, informe fiscal anual generado y contrastado con los datos del broker.

## Fase 6: capas avanzadas

Las capas avanzadas se añaden de una en una, cada una detrás de un interruptor y con una sola pregunta de aceptación: ¿mejora el resultado neto frente a la misma estrategia sin ella, en el walk-forward y después en la arena? Si no, se desactiva. Cada capa se prueba creando una variante del agente base (por ejemplo "tendencia + régimen") que compite contra el original.

| Orden | Capa | Qué añade | Datos e implementación | Dificultad |
| --- | --- | --- | --- | --- |
| 1 | Régimen de mercado | Etiqueta cada día (tendencia o lateral, volatilidad alta o baja) y activa, desactiva o reescala estrategias | Calculado con los precios que ya hay. Primero reglas simples (media de 200 días y percentil de volatilidad); después, si compensa, un modelo oculto de Markov. Se expone como un campo `regime` en `MarketSnapshot` | Media |
| 2 | Datos macro | Contexto para repartir entre renta variable, bonos y efectivo | Tipos de interés, pendiente de la curva, inflación, desde la API de FRED o el portal de datos del BCE. Se guarda la fecha de publicación de cada dato, porque los valores revisados después introducen sesgo de futuro | Media |
| 3 | Meta-agente | Reparte el capital entre estrategias según su comportamiento reciente | Trabaja sobre las series de retornos de las estrategias. Pesos suavizados y con tope para no perseguir al que acaba de ganar; reequilibrio mensual | Media-alta |
| 4 | Noticias y sentimiento con LLM | Filtro de riesgo ante eventos graves; después, posible señal complementaria | Ver el detalle debajo | Alta |
| 5 | ML sobre precios | Busca patrones que las reglas simples no ven | Modelos de árboles con gradient boosting, validación cruzada con purga y embargo para no filtrar información entre entrenamiento y test. Se entrena en Python y se exporta (por ejemplo a ONNX, que tiene runtime Java), o se usa una librería Java como Smile o Tribuo | Alta |

**Noticias y sentimiento: cómo sería la tubería**

1. **Fuentes con licencia clara:** RSS y APIs de noticias de pago, y comunicados oficiales (por ejemplo hechos relevantes de la CNMV o los registros de la SEC para valores de EEUU). Rascar webs de medios tiene riesgo legal.
2. **Normalización:** deduplicado, marca de tiempo exacta de publicación y asociación a instrumentos.
3. **Extracción con un LLM:** por cada noticia, salida estructurada con instrumento afectado, tipo de evento, dirección (positiva, negativa o neutra) y gravedad, validada contra un esquema JSON.
4. **Almacenamiento versionado:** se guarda el texto, el resultado, el modelo y la versión del *prompt* usados.
5. **Uso inicial como filtro:** si aparece un evento grave sobre un activo en cartera, el gestor de riesgo reduce exposición o bloquea compras. No genera órdenes por sí mismo.

**El problema de validar noticias con LLM**

Un modelo de lenguaje entrenado con textos históricos puede "conocer" lo que pasó después de una noticia antigua, así que un backtest de noticias pasadas queda contaminado y sale mejor de lo que sería en real. Por eso esta capa se valida sobre todo hacia delante, en la arena y con datos que el modelo no ha visto, y sus resultados de backtest se toman con mucha cautela. A esto se suma el coste por noticia y el de las licencias de datos.

**Criterio de aceptación de cada capa:** mejora neta de costes y de impuestos frente a la variante sin la capa, en la mayoría de ventanas del walk-forward y sin empeorar la caída máxima; después, al menos 3 meses de arena en paralelo con la versión base.

## Riesgos y criterios de parada

Los mayores riesgos del proyecto no son de programación sino de método: engañarse con un backtest bonito, subestimar costes y operar con un sistema que se ha degradado sin que nadie lo note.

| Riesgo | Cómo se manifiesta | Mitigación en el plan |
| --- | --- | --- |
| Sobreajuste | Resultados excelentes en el pasado y flojos en vivo | Muestra fuera de datos, walk-forward, sensibilidad de parámetros, registro de intentos, agentes aleatorios de control |
| Sesgo de futuro | El backtest usa información que aún no existía | Decisión en t y ejecución en t+1, fecha de disponibilidad en cada dato, test de no anticipación |
| Costes subestimados | La estrategia rinde en simulación y no en real | Modelo de costes con comisión, spread, slippage y divisa; agente sombra que recalibra con ejecuciones reales |
| Impuestos ignorados | La rentabilidad neta es mucho menor que la bruta | Métricas netas de IRPF y regla de los dos meses en `TaxLedger` |
| Errores de ejecución | Órdenes duplicadas, perdidas o fuera de precio | Máquina de estados, claves de idempotencia, reconciliación, control de precio razonable, tests de fallos |
| Caída de la API del broker o cambios en la beta | El bot no ve el mercado o no confirma una orden | Reintentos con cuidado, latido externo, alertas, manual de operación y tests de contrato contra el demo para detectar cambios de la API |
| Concentración oculta | Varios agentes compran a la vez el mismo ETF y la exposición real de la cuenta se dispara | Límites de riesgo también a nivel de cuenta, no solo por agente |
| Datos defectuosos | Un precio erróneo dispara una operación | Validaciones de calidad, doble fuente, control de precio razonable |
| Degradación de la estrategia | Deja de funcionar con el tiempo | Monitorización continua, apagado automático por caída, revisión periódica |
| Poca muestra | Se confunde suerte con habilidad en pocos meses | Criterios fijados de antemano y comparación con controles aleatorios |
| Seguridad | Credenciales o acceso a la API expuestos | Secretos fuera del repositorio, API solo local, permisos mínimos |
| Dependencia de una persona | Nadie más entiende el sistema | Documentación, manual de operación y configuración versionada |

**Criterios de parada (cuándo no seguir o frenar)**

1. Ninguna estrategia cumple el criterio de la fase 2: se cambia de familia de ideas o se concluye que lo razonable es mantener un ETF global.
2. En la arena, el comportamiento en vivo diverge de forma sostenida del backtest sin explicación.
3. En real, un agente supera su límite de caída desde máximos: se apaga solo y no se reactiva sin revisión humana.
4. Una discrepancia de reconciliación sin causa conocida: se detiene toda la operativa.
5. Los costes y los impuestos se comen la ventaja: el agente se retira.

Hay un límite que no cambia con la tecnología: solo se arriesga capital que se pueda perder sin que afecte a la vida diaria, y el capital se aumenta por la evidencia acumulada, no por la impaciencia.

## Roadmap y primeros pasos

Las fases 0 a 3 son el grueso del trabajo de programación y suman unos 3 a 5 meses a tiempo parcial (estimación orientativa); la fase 4 es tiempo de espera y la 6 queda abierta.

&#91;embedded content: roadmap · 7 fases con su puerta de salida\]

El rombo resaltado es la puerta previa al dinero real: hasta que un agente no la supera, el bot no toca la cuenta real.

**Primeros pasos concretos**

1. Abrir la cuenta de eToro y completar la verificación sin depositar, generar la clave de API de demo y comprobar en el catálogo (como ETFs reales, no CFD) qué ETFs del universo están disponibles.
2. Crear el repositorio con el esqueleto Maven multimódulo y un Docker Compose con PostgreSQL.
3. Elegir los 10 a 15 ETFs UCITS de acumulación en euros del universo y cargar la tabla `instrument` con sus ISIN.
4. Descargar el histórico de 2 o 3 ETFs y guardarlo, solo para probar la tubería.
5. Implementar la interfaz `Strategy` y el backtest de la media de 200 días contra el benchmark: es el primer resultado real y llega en pocas semanas.

## Decisiones cerradas

Estas decisiones sustituyen a las que estaban abiertas y son el punto de partida de los ADR del proyecto: cada fila será un ADR.

| Decisión | Resolución | Motivo |
| --- | --- | --- |
| Universo v1 | 10 a 15 ETFs UCITS de acumulación, cotizados en euros en Xetra y con 12 años o más de historia | Sin divisas ni dividendos, comisiones más bajas y sin el sesgo de elegir acciones con el pasado a la vista. Las acciones se reabren tras la fase 5 |
| Estrategias v1 | Tendencia, momentum con rotación, volatilidad objetivo y swing sobre ETFs | Cubre del largo plazo a días y semanas sin depender de acciones sueltas |
| Ritmo | Una decisión al día tras el cierre de Xetra y ejecución al día siguiente | Es el horizonte pautado acordado y evita la infraestructura de alta frecuencia |
| Ejecución | Simulada al precio de apertura de t+1 con slippage extra; real con órdenes limitadas poco después de la apertura | Evita depender de una subasta; el agente sombra mide la diferencia y el modelo se recalibra |
| Datos históricos | Sin pago por defecto. Prueba de dos días en la fase 1 comparando Stooq y Yahoo entre sí; se elige la primera fuente con 12 años o más para el 90 % del universo y diferencias inferiores al 0,5 % entre ambas en el último año. Si ninguna cumple, un ADR decide pagar un proveedor con tope mensual | La profundidad de las velas de eToro no está comprobada y la fábrica prohíbe licencias de pago por defecto; si ninguna fuente gratuita sirve, EODHD cuesta unos 19,99 $ al mes y basta un mes para descargar el histórico una vez |
| Broker y tarifa | eToro (cuenta en euros, ETFs reales sin apalancamiento) con su API pública beta, primero en demo y sin depósito. Trading 212 queda excluido porque sus términos de API prohíben el trading algorítmico; IBKR queda como adaptador futuro opcional si el capital supera unos 2.000–5.000 € | Con 100 € hace falta 0 € de comisión, órdenes por importe y que la automatización sea legal. Trading 212 la prohíbe en sus términos; IBKR rechaza órdenes fraccionadas por API y cuesta unos 2,40 € por operación; XTB cerró su API en marzo de 2025; Alpaca, Freedom24, Trade Republic y Scalable no encajan (sin acceso, con 2 € por orden o sin API oficial) |
| Capital | Simulado: 100 € por agente, igual que el real. Real inicial: 100 € con un solo agente de baja rotación | Sin comisión, el coste es el spread. Con el tope del 25 % por activo, cada posición ronda los 25 €, así que el mínimo por orden de eToro (unos 10 $, no comprobado) puede limitar el universo. Con 100 € la muestra estadística y los impuestos son casi irrelevantes: se invierte para validar el sistema, no para ganar dinero |
| Escalado | Como máximo duplicar el capital cada 3 meses y solo tras 3 meses sin incidentes, con un tope fijado por ti | Evita escalar por impaciencia |
| Despliegue | Desarrollo en local; operación en un servidor Linux pequeño (VPS o mini PC) con Docker Compose | Un proceso diario no necesita Kubernetes; es una excepción a la ADR-0005 de la fábrica |
| Sesión del broker | Una clave de API por entorno (demo y real) guardada como secreto. Si la API no responde, devuelve 429 sostenido o rechaza la clave, el bot no opera y avisa; eToro puede suspender el acceso ante tráfico anómalo | No hay sesión interactiva ni doble factor periódico; el riesgo pasa a ser que la API beta cambie |
| Alertas e informes | Telegram e informe HTML nocturno; Grafana opcional tras la fase 4 | Mínima infraestructura para empezar |
| Stack | Java 21, Maven multimódulo, Spring Boot, PostgreSQL y ta4j, sin Lombok (records y constructores) | Coincide con las ADR-0002 y ADR-0027 de la fábrica |
| Tiempo | `java.time` con `Clock` inyectable, UTC en base de datos y calendario por bolsa | Tests deterministas y sin errores por cambio de hora |
| Umbrales de la fase 2 | Los últimos 3 años quedan como holdout y se miran una sola vez. Walk-forward con 5 años de entrenamiento, 6 meses de test y paso de 6 meses (unas 8 ventanas con 12 años). Superar al benchmark neto en Sharpe o Calmar en el 70 % de las ventanas, estabilidad ante cambios de parámetros del 20 % y 30 operaciones o más en swing | Se fijan antes de ver resultados para no ajustarlos después |
| Umbrales de la fase 4 | Mismas decisiones que el backtest en el 95 % de los días y diferencia de patrimonio no superior al 0,5 % del capital; por encima del percentil 90 de los agentes aleatorios; al menos 6 meses | Da una definición medible de "el vivo coincide con el backtest" |
| Límites de riesgo | 25 % máximo por activo, 2 % de pérdida diaria y apagado del agente con una caída del 15 % desde máximos | Valores de partida conservadores, ajustables solo con un ADR |
| Impuestos | Un asesor fiscal revisa `TaxLedger` antes de la primera declaración con operaciones reales; aviso del modelo 720 al llegar a 40.000 € | El umbral legal es de 50.000 € por categoría |
| Fuera de la v1 | Acciones, divisas, noticias con LLM, ML sobre precios y meta-agente | Entran tras la fase 5 y en el orden de la fase 6 |
| Repositorio | Privado en GitHub, nombre `etf-trading-bot`, paquete base `com.adrianrevilla.trading` (editable), Conventional Commits y un ADR por cada fila de esta tabla | Es lo que pide la fábrica |
| Adaptador en Python | No hace falta: eToro es una API REST y se llama desde Java | Una pieza menos; si un broker futuro solo ofreciera SDK en Python, iría como proceso aparte tras la interfaz Broker |

## Encaje con ai-project-factory

La fábrica sirve como proceso y estándares, no como plantilla, y trae por defecto una infraestructura de empresa (Kubernetes, AWS, React) que este bot no necesita; esas excepciones se declaran desde el principio.

**Cómo se usa.** Es un [repositorio de guías](https://github.com/adrianrevilla009/ai-project-factory) sin plantillas ni generador (ADR-0038): el agente construye el proyecto en otra carpeta con `/project-init <ruta-destino> <nombre>`, lanzado desde Claude Code abierto en la fábrica. Entre fases hay seis gates humanos, y una persona debe crear `.factory/state.json` y `approvals/` en el destino, porque los agentes no pueden escribirlos ([guía de uso](https://github.com/adrianrevilla009/ai-project-factory/blob/main/docs/onboarding.md)).

**Se adopta tal cual**

- Maven multimódulo (ADR-0002), CI con GitHub Actions (ADR-0020), sin Lombok (ADR-0027) y artefactos sin licencias de pago (ADR-0016).
- Conventional Commits, un ADR por decisión y declarar «no comprobado» lo que no se haya verificado. En este proyecto aplica también a la rentabilidad: ninguna estrategia se da por buena sin backtest validado y arena.
- Documentación en español y código e identificadores en inglés.

**Excepciones que hay que registrar**

| Valor por defecto de la fábrica | En este proyecto | Cómo se trata |
| --- | --- | --- |
| Kubernetes local en vez de Docker Compose (ADR-0005) | Docker Compose | Excepción justificada en el Gate 1 |
| Persistencia políglota por defecto (ADR-0013) | Solo PostgreSQL | Decisión escrita en un ADR del proyecto |
| Frontend React activado por defecto | Sin frontend en la v1 | Responder "no" en la entrevista |
| AWS, Terraform, Vault, Keycloak e Istio (ADR-0003, 0008, 0010, 0012 y 0009) | No se usan; secretos por variables de entorno | No aplica |
| Gate 5, revisión cloud | No hay cloud | No comprobado si puede cerrarse con una excepción; se verifica en el primer flujo |
| Módulos siempre activos `local-k8s`, `devcontainer` y `observability` | Solo lo mínimo | No comprobado cuánto se puede desactivar; se verifica en `/capability-select` |

**Fases del bot y gates (propuesta)**

- Este documento es la entrada del intake (Gate 0) y del plan (Gate 1).
- Las fases 2 y 3 se implementan módulo a módulo con su PR (Gates 2 y 3).
- La puerta previa al dinero real, al final de la fase 4, se firma como Gate 4 (release), con los criterios de la fase 4 añadidos a su checklist.

**Respuestas propuestas para la entrevista de intake**

- No a: `multi_tenant`, `search`, `analytics`, `documents_ocr`, `chatbot_rag`, `workflow_bpm`, `payments`, `realtime`, `back_office` y `special_category_data`.
- `notifications`: sí, por Telegram.
- `full_history`: no; basta un registro inmutable del libro de operaciones, como indica la ayuda de la propia pregunta.
- `resilience_tier`: 3 (backup y restauración, con objetivos de recuperación de 24 horas), el tier previsto para sistemas no críticos.
- `frontend`: no (el valor por defecto es sí, hay que cambiarlo).

**Siguiente paso:** abrir Claude Code en la fábrica y lanzar `/project-init <ruta> etf-trading-bot`, usando este documento como brief; los gates los firmas tú.

## Fuentes

Consultadas el 19 de noviembre de 2026. Las cifras de tarifas de brokers y los umbrales de aceptación del plan no proceden de estas fuentes: son propuestas por confirmar.

- [Interactive Brokers: documentación de la TWS API y paper trading](https://ibkrcampus.com/campus/ibkr-api-page/twsapi-doc/)
- [Interactive Brokers: configuración inicial de la TWS API](https://interactivebrokers.github.io/tws-api/initial_setup.html)
- [Interactive Brokers: normativa PRIIPs y KID](https://ibkb.interactivebrokers.com/es/node/2993)
- [ta4j: biblioteca de análisis técnico para Java](https://github.com/ta4j/ta4j) y su [documentación](https://ta4j.github.io/ta4j-wiki/Home.html)
- [QuantConnect: mapeo de ETFs UCITS para usuarios de IB en la UE](https://www.quantconnect.com/forum/discussion/20468/feature-request-ucits-etf-mapping-for-eu-based-ib-users/)
- [Billeo: ganancias patrimoniales y tipos del ahorro 2026](https://www.billeo.es/blog/ganancias-patrimoniales-venta-acciones-2026)
- [OCU: cómo tributan las inversiones](https://www.ocu.org/inversiones/fiscalidad-y-derechos/impuestos/articulos/2026/02/peaje-fiscal-de-sus-inversiones-y-ahorros)
- [CNMV: guía de fiscalidad de acciones 2026](https://internet.cnmv.es/DocPortal/Publicaciones/Guias/GuiaFiscalidadAcciones2026.pdf)
- [Hacecuentas: regla FIFO y regla de los dos meses](https://hacecuentas.com/es/impuestos/ahorro-e-inversiones)
- [El Club de Inversión: obligaciones fiscales con Interactive Brokers](https://www.elclubdeinversion.com/ufaq/que-obligaciones-fiscales-tengo-si-opero-con-interactive-brokers-desde-espana/)
- [Allinvestview: modelo 720 con broker extranjero](https://www.allinvestview.com/es/modelo-720-broker-extranjero/)
- [Rankia: modelo 720](https://www.rankia.com/blog/impuestos/1623888-modelo-720-que-como-rellenarlo?page=15)

Añadidas en la revisión del 19 de noviembre de 2026:

- [Interactive Brokers: el IB Gateway y su reinicio diario](https://www.interactivebrokers.com/docs/tws-api/doc/architecture/the-trader-workstation/the-ib-gateway)
- [Interactive Brokers: reautenticación diaria y semanal](https://www.interactivebrokers.com/docs/third-party-integrations/tws-settings/daily-weekly-reauthentication)
- [IBKR Campus: mantener un sistema con la API en marcha sin supervisión](https://www.interactivebrokers.com/campus/?p=259814)
- [Interactive Brokers: límites de datos de mercado por API](https://www.interactivebrokers.com/docs/tws-api/doc/md/introduction.md)
- [EU Personal Finance: comisiones de Interactive Brokers en Europa](https://www.eupersonalfinance.eu/articles/interactive-brokers-fees-europe)
- [EU Personal Finance: IBKR Tiered frente a Fixed](https://www.eupersonalfinance.eu/articles/ibkr-tiered-vs-fixed)
- [ai-project-factory: repositorio de la fábrica](https://github.com/adrianrevilla009/ai-project-factory), con su [README](https://github.com/adrianrevilla009/ai-project-factory/blob/main/README.md), la [guía de uso](https://github.com/adrianrevilla009/ai-project-factory/blob/main/docs/onboarding.md) y las [ADR](https://github.com/adrianrevilla009/ai-project-factory/tree/main/catalog/adr)

Añadidas al cambiar de broker por el capital de 100 €:

- [eToro: portal de la API pública](https://api-portal.etoro.com/)
- [eToro Builders: condiciones del programa (PDF)](https://www.etoro.com/wp-content/uploads/2026/03/Master_eToro_Builders_Economy_Terms_17-Feb-2026-clean_R.pdf)
- [Trading 212: términos de uso de la API (motivo de exclusión, cláusula 4.2(a))](https://trading212.com/legal-documentation/API-Terms_EN.pdf)
- [XTB: cierre de su API en marzo de 2025](https://www.xtb.com/cy/help-center/our-platforms-2/do-you-offer-api-2)
- [eToro Builders: documentación para desarrolladores](https://builders.etoro.com/)
- [EODHD: planes de datos de pago](https://eodhd.com/financial-summary/XSX6.XETRA)

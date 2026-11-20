# 0025. Arquitectura hexagonal en multimódulo, DDD táctico y CQRS de nivel 1

- **Estado:** Aceptada
- **Fecha:** 2026-11-20
- **Fuente:** guías de [ai-project-factory](https://github.com/adrianrevilla009/ai-project-factory) (`hexagonal`, `ddd-tactical`, `cqrs`, `maven-multimodule-layout`, `layering-rules-archunit`); [TRD](../TRD.md) sección 3

## Contexto

El brief adopta la fábrica como estándar, pero hasta esta fecha el código no aplicaba sus guías. Una auditoría encontró:
cada módulo era un único paquete plano; `trading-app` mezclaba en un paquete los jobs (lógica de aplicación), los
adaptadores JDBC, Telegram y los servidores HTTP; los jobs dependían de clases concretas `Jdbc*`; `BarRepository` era una
clase JDBC dentro del módulo de dominio de datos; la lógica de agregados (`Agent`) se manipulaba desde fuera por acceso de
paquete; y no había ninguna prueba que impidiera un `import` indebido. Esto contradecía las reglas MUST de la fábrica.

## Decisión

**Capas y paquetes.** Cada módulo de contexto usa `domain`, `application` y `adapter.{in,out}`. Las dependencias apuntan
hacia el dominio: `adapter → application → domain`. `app` (arranque) lo cablea todo y nadie depende de él.

| Módulo | Contenido |
| --- | --- |
| `trading-core`, `-data`, `-strategies`, `-risk`, `-backtest`, `-execution`, `-arena`, `-tax` | Un contexto cada uno: `domain` (modelo y reglas), `application` (servicios y puertos de salida) y, si acaso, adaptadores que solo usan el JDK (`adapter.out.report`, `.csv`, `.memory`, `.dryrun`). **Sin Spring, JDBC, Jackson ni HTTP.** |
| `trading-application` | Casos de uso que cruzan contextos: puertos de entrada (`RunTradingDay`, `RunArenaDay`, `IngestMarketData`, `CheckSystemHealth`, `RunBacktest`), servicios que los implementan y puertos de salida (`TradingDayLog`, `ArenaJournal`, `MonitoringQueries`, `BusinessReadModel`) |
| `trading-adapter-persistence` | PostgreSQL por JDBC y las migraciones Flyway |
| `trading-adapter-marketdata` | Yahoo y CSV |
| `trading-adapter-notification` | Telegram (alertas y comandos) y alertador de log |
| `trading-adapter-web` | Página de estado y endpoint del kill switch |
| `trading-adapter-metrics` | Métricas de negocio con Micrometer |
| `trading-broker-sim`, `trading-broker-etoro` | Simulador (**dominio**, ver abajo) y adaptador REST de eToro |
| `trading-app` | Módulo de arranque: `@SpringBootApplication`, configuración, disparadores `@Scheduled` y CLI. Sin lógica de negocio |

**Puertos.** Entrada: interfaces de caso de uso que invocan los adaptadores de entrada (scheduler, Telegram, HTTP, CLI).
Salida: interfaces que implementan los adaptadores. Un puerto que necesita el dominio (`KillSwitch`, `Broker`) vive en
`domain`; el resto en `application.port.out`. Los puertos de salida de lectura devuelven DTO propios, no entidades.

**El simulador es dominio.** `SimulatedBroker` es un modelo del mercado, sin infraestructura; lo usan el motor de backtest y la
arena. Por eso `Broker` y sus excepciones son parte del dominio y el simulador está en `brokersim.domain`. `EToroBroker`, que
sí habla HTTP, es un adaptador.

**Reglas ejecutables** (`trading-app/src/test/.../architecture`, ArchUnit, fallan `mvn verify`): el dominio y la aplicación no
dependen de frameworks ni de infraestructura (la aplicación solo puede usar la fachada SLF4J); las capas apuntan hacia
dentro; los adaptadores no dependen unos de otros; los contextos no tienen ciclos; `core` no depende de nada del proyecto;
las estrategias no conocen brókers ni ejecución (ADR-0021); riesgo no depende de ejecución ni de estrategias; los puertos
son interfaces; sin Lombok. Cada regla tiene una prueba **negativa** con una clase que la viola, para que no pueda
debilitarse sin que falle una prueba. Una regla no se relaja para que pase el build: se corrige el código o se escribe un ADR.

**DDD táctico.** Los conceptos sin identidad son records inmutables (`Isin`, `Bar`, `TargetPortfolio`, `OrderRequest`). `Agent` es
la raíz de su agregado: el estado (equity, pico, decisiones, error) es privado y cambia con verbos (`recordEquity`,
`recordDecision`, `recordFailure`, `moveTo`); la transición de estado ilegal lanza excepción. El ledger es de solo añadir y las
referencias entre agregados son por identidad (`agentId`, `orderId`).

**CQRS de nivel 1.** Lectura y escritura están separadas a nivel de código: los casos de uso son los comandos; las consultas son
puertos de lectura (`MonitoringQueries`, `BusinessReadModel`) con DTO propios, sobre la misma base de datos. Sin segundo
almacén, sin proyecciones asíncronas, sin event sourcing: no hay carga ni forma de lectura que lo justifiquen, y la guía dice
que no es el valor por defecto.

**Configuración.** La configuración propia se enlaza con `@ConfigurationProperties` sobre records inmutables (`IngestionProperties`,
`ArenaProperties`, `TelegramProperties`, `KillSwitchProperties`, `MonitoringProperties`) con validación en el constructor compacto:
un valor inválido impide el arranque, no rompe la primera noche. No hay `@Value` en el código (estándar `04-spring-platform`).
Se valida a mano y no con Jakarta Validation para no añadir `hibernate-validator` al classpath del módulo de arranque.

## Excepciones a la fábrica, registradas

- **Nombre `trading-app` y no `trading-boot`.** Es solo un nombre; renombrarlo afectaría al Dockerfile, a Sonar y al CI sin
  ganancia. Cumple lo esencial: es el único módulo con `@SpringBootApplication` y `spring-boot-maven-plugin`.
- **Dominio y aplicación de un contexto comparten módulo** (`trading-risk` contiene `risk.domain`) en vez de `*-domain` y
  `*-application` separados. Tres módulos por contexto serían 24 módulos para un solo proceso. La separación se impone por
  paquete y con ArchUnit, y el classpath de esos módulos no tiene Spring.
- **Adaptadores de JDK puro dentro del módulo del contexto** (informes HTML/CSV, repositorio en memoria, bróker de ensayo):
  no usan ningún framework; tener un módulo por cada uno sería ceremonia sin beneficio.

## Consecuencias

- Una violación (`import` de JDBC en el dominio, un adaptador que llama a otro, un puerto que no es interfaz) rompe el build
  en el PR que la introduce.
- Cambiar la base de datos, el proveedor de datos o la forma de alertar toca un solo módulo adaptador.
- Más clases y módulos: 17 módulos frente a 11, y mapeos entre puertos y adaptadores. Es el precio que dice la guía y aquí se
  paga porque hay dominio con reglas (órdenes, riesgo, impuestos) que proteger.
- **Pendiente, declarado:** `Order` no es una raíz de agregado con comportamiento; su ciclo de vida vive en `OrderStateMachine`
  y `ExecutionService`. Está muy probado (concurrencia, idempotencia, atomicidad) y reescribirlo ahora arriesgaría el código
  que mueve dinero sin ganancia inmediata. Se revisará si crece la lógica de órdenes.
- Las pruebas de integración multi-módulo viven en `trading-app`; las de un adaptador, en su módulo.

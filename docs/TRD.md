# TRD: Definición técnica de `etf-trading-bot`

- **Estado:** borrador para Gate 1
- **Fecha:** 2026-11-19
- **Documentos relacionados:** [PRD](PRD.md) · [ADR](adr/README.md) · [brief](brief.md)

## 1. Visión de arquitectura

Cadena única, idéntica en backtest, arena y real:

```
Datos ──► MarketSnapshot ──► Strategy ──► TargetPortfolio ──► RiskManager ──► Broker
 (PG)        (hasta t)        (pura)      (pesos ≤100 %)     (veto/recorte)   (Sim | eToro)
                                                                                  │
                         PostgreSQL: instrument, bars, order, fill, cash_ledger ◄─┘
```

Principios:

1. **La estrategia no sabe en qué modo corre.** Solo ve `MarketSnapshot` y `PortfolioState` y devuelve pesos objetivo (ADR-0021).
2. **Riesgo independiente y con la última palabra** sobre cualquier orden.
3. **El modo se decide por configuración** (qué implementación de `Broker`), no por código.
4. **Decisión en t, ejecución en t+1.** Esa separación impide el sesgo de futuro.
5. **Persistir antes de enviar.** Toda orden se guarda en BD antes de salir al broker.

## 2. Stack

| Área | Elección | ADR |
| --- | --- | --- |
| Lenguaje | Java 21 (mínimo de ta4j 0.22) | 0012 |
| Framework | Spring Boot (DI, `@Scheduled`, config por perfil) | 0012 |
| Build | Maven multimódulo, sin Lombok (records y constructores) | 0012 |
| BD | PostgreSQL + Flyway; solo PostgreSQL | 0012, 0023 |
| Acceso a datos | `JdbcTemplate` o jOOQ (SQL explícito) | 0012 |
| Dinero | `BigDecimal`; `double` solo en estadística | 0012 |
| Indicadores | ta4j (señales); motor de cartera propio | 0024 |
| Cliente HTTP | `java.net.http.HttpClient` estándar | 0020 |
| Tests | JUnit 5, AssertJ, Testcontainers, broker falso | 0012 |
| Observabilidad | SLF4J/Logback estructurado; Telegram Bot API | 0011 |
| Despliegue | Docker Compose en VPS o mini PC Linux | 0009 |
| Config | YAML por entorno `dev`, `paper`, `live`; secretos en env | 0010 |
| Tiempo | `java.time`, `Clock` inyectable, UTC en BD | 0013 |
| CI | GitHub Actions | 0019 |

## 3. Módulos Maven y capas

La arquitectura es hexagonal ([ADR-0025](adr/0025-arquitectura-hexagonal.md)). Dentro de cada contexto hay `domain`, `application` y `adapter`; los módulos de adaptador aíslan cada tecnología.

| Módulo | Responsabilidad | Depende de |
| --- | --- | --- |
| `trading-core` | Núcleo compartido: dominio (`Strategy`, `Broker`, `RiskRule`, `MarketSnapshot`, `PortfolioState`, `TargetPortfolio`, `KillSwitch`) y puertos `Alerter`, `InstrumentCatalog` | — |
| `trading-data` | Calendario, calidad, limpieza, ingesta; puertos `BarRepository`, `BarSource` | core |
| `trading-backtest` | Motor de simulación, costes, validación; puerto de informes | core, broker-sim |
| `trading-strategies` | Las cuatro estrategias y sus parámetros | core (+ ta4j) |
| `trading-risk` | Reglas de riesgo | core |
| `trading-execution` | Máquina de estados de órdenes, `ExecutionService`, reconciliación, `RealTradingGate`; puertos `OrderRepository`, `BrokerRefStore` | core |
| `trading-broker-sim` | `SimulatedBroker` y `FaultInjectingBroker` (dominio: modelo del mercado) | core |
| `trading-arena` | Agentes, orquestador, controles, métricas; puerto de informes | core, backtest, risk, strategies, broker-sim |
| `trading-tax` | `TaxLedger`, exportaciones; puerto `TaxTradeSource` | core |
| `trading-application` | Casos de uso y servicios entre contextos; puertos de entrada y de salida (escritura y lectura) | todos los contextos |
| `trading-adapter-persistence` | JDBC y migraciones Flyway | contextos, application |
| `trading-adapter-marketdata` | Yahoo y CSV | core, data |
| `trading-adapter-notification` | Telegram | core, application |
| `trading-adapter-web` | Página de estado y kill switch | core, application |
| `trading-adapter-metrics` | Micrometer | application |
| `trading-broker-etoro` | `EToroBroker` (REST) | core, execution |
| `trading-app` | Arranque Spring: configuración y disparadores | todos |

Reglas (las comprueba ArchUnit en `mvn verify`): `domain` y `application` no usan frameworks ni infraestructura; `adapter → application → domain`; los adaptadores no dependen unos de otros; `core` no depende de nadie; `strategies` no depende de brókers; ningún módulo depende de `app`. `trading-broker-ib` queda como adaptador futuro opcional.

## 4. Contratos clave

```java
public interface Strategy {
    String id();
    TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state);
}
```

```java
public interface Broker {
    OrderAck submit(OrderRequest request);      // persistida antes por el llamador
    void cancel(OrderId id);
    Optional<OrderStatus> status(OrderId id);
    List<Position> positions();
    CashBalance cash();
}

public interface RiskRule {
    RiskDecision evaluate(ProposedOrder order, PortfolioState state, RiskConfig config);
}
```

- `MarketSnapshot`: datos hasta el cierre de t, cada dato con su fecha de disponibilidad; campo opcional `regime` (fase 6).
- `TargetPortfolio`: pesos por instrumento, suma ≤ 100 %, el resto efectivo.
- Las firmas son indicativas; se ajustan al implementar el módulo y se reflejan en el código.

## 5. Modelo de datos (PostgreSQL + Flyway)

| Tabla | Contenido | Clave |
| --- | --- | --- |
| `instrument` | ISIN, bolsa, divisa, id eToro, tipo (ACC/DIST) | ISIN + bolsa |
| `bar_daily_raw` | OHLCV tal como llega por fuente | instrumento + fecha + fuente |
| `bar_daily` | Serie limpia, regenerable desde raw | instrumento + fecha |
| `fx_rate` | Cambio diario a EUR (v2) | divisa + fecha |
| `corporate_action` | Dividendos, splits, cambios de ticker | instrumento + fecha + tipo |
| `trading_calendar` | Días de negociación por bolsa | bolsa + fecha |
| `trade_order`, `fill`, `cash_ledger` (`order` es palabra reservada en SQL) | Libro de operaciones, inmutable (solo inserciones; correcciones como asientos compensatorios) | ids |
| `data_quality_issue` | Anomalías y estado | id |
| `backtest_run` | Config, hash de datos, commit, métricas, informe | run id |
| `agent`, `agent_state` | Configuración y estado del agente, ciclo de vida | agent id |
| `trial_log` | Registro de variantes probadas por familia de estrategia | id |
| `decision_audit` | Decisión de cada agente por día, propuesta, resultado de riesgo | agent + fecha |

Migraciones versionadas en Flyway; nunca se editan las ya aplicadas. Todas las marcas de tiempo en UTC.

## 6. Datos (fase 1)

- **Fuentes:** velas de eToro (profundidad no comprobada); histórico externo gratuito (Stooq/Yahoo) por defecto, de pago solo por ADR (ADR-0005).
- **Calidad en cada ingesta:** huecos frente al calendario, precios ≤0, saltos >10 % sin evento corporativo, volumen cero, duplicados, discrepancias entre fuentes. Resultado en `data_quality_issue`; errores críticos bloquean la decisión del día.
- **Sin sesgo de futuro:** `available_from` por dato; el `MarketSnapshot` filtra por él.
- **Sin sesgo de supervivencia:** los activos que dejan de cotizar se conservan.
- **Versionado:** hash del conjunto de datos por backtest.
- **Job de ingesta:** `@Scheduled` tras el cierre de Xetra, idempotente (upsert), reintentos con espera creciente, alerta por Telegram.

## 7. Backtest y validación (fase 2)

- **Bucle diario:** construir snapshot hasta t → `Strategy.decide` → `RiskManager` → simular ejecución a la apertura de t+1 con spread y slippage prudentes.
- **Costes:** comisión 0 € en ETFs reales eToro; medio spread en pb por ETF; slippage adicional; coste de divisa solo si no es EUR (no aplica en v1); IRPF opcional.
- **Validación:** holdout de los últimos 3 años (se mira una vez); walk-forward 5 años de entrenamiento / 6 meses de test / paso 6 meses (~8 ventanas con 12 años); sensibilidad ±20 %; `trial_log`; bootstrap/Monte Carlo de operaciones.
- **Tests del motor:** series sintéticas con respuesta conocida; test de no anticipación (alterar datos futuros no cambia decisiones pasadas).
- **Métricas netas:** CAGR, volatilidad, Sharpe, Sortino, MaxDD, Calmar, rotación, % invertido, nº de operaciones, alfa, beta, tracking error.
- **Informe:** HTML y CSV, con run id, config, hash de datos y commit.

## 8. Riesgo y ejecución (fase 3)

### 8.1 Reglas de riesgo (configuración YAML, no código)

| Regla | Acción |
| --- | --- |
| Peso máximo por activo (25 %) | Recorta |
| Exposición total | Recorta |
| Pérdida diaria máxima (2 %) | Bloquea compras hasta el día siguiente |
| Caída desde máximos del agente (15 %) | Apaga el agente y avisa |
| Lista blanca de ISIN | Rechaza |
| Liquidez mínima | Recorta o rechaza |
| Precio razonable (fat finger) | Rechaza |
| Orden duplicada del día | Rechaza |
| Horario de mercado / subasta | Aplaza |

Los límites se aplican por agente **y** a nivel de cuenta (concentración oculta). Cambiar un valor de partida exige un ADR (ADR-0016).

### 8.2 Kill switch

Cancela órdenes abiertas y bloquea nuevas. Activación por comando de Telegram, flag en BD o endpoint protegido. No liquida posiciones. Se ensaya antes de operar en real.

### 8.3 `EToroBroker`

- Cabeceras `x-api-key`, `x-user-key`, `x-request-id` (UUID por petición).
- Lecturas: `https://public-api.etoro.com/api/v1/`. Órdenes demo: `POST /api/v2/trading/execution/demo/orders`; reales: `/api/v2/trading/execution/orders`.
- Órdenes por importe, `leverage = 1`, `settlementType = real`. Antes de cada orden real se consulta el coste estimado (what-if) y se aborta si no sale como activo real.
- Límites 60 lecturas / 20 escrituras por minuto y clave: limitador propio (token bucket) y espera según `Retry-After` en 429.
- Sin clave de idempotencia nativa: la orden se guarda antes de enviar; si no hay respuesta se consulta órdenes y posiciones antes de reintentar. Una orden nunca se reenvía a ciegas.
- Rotaciones: primero la venta, después la compra, escalonadas (condiciones de eToro sobre ETFs similares).
- Tipo de orden: limitada poco después de la apertura, evitando subastas (ADR-0004); si la API real solo admite mercado, se registra y se mide el slippage.
- Un hilo de decisión por job y sondeo periódico del estado de órdenes (ADR-0012).

### 8.4 Máquina de estados de orden

`CREATED → SUBMITTED → PARTIALLY_FILLED → FILLED`, con salidas a `CANCELLED` y `REJECTED`. Persistida antes de enviar, con clave de idempotencia interna propia.

### 8.5 Reconciliación

Al arrancar y tras operar compara posiciones y efectivo del broker con el ledger. Si la diferencia supera la tolerancia, detiene la operativa y avisa; no corrige nada por sí sola.

### 8.6 Tests de fallos

Broker falso con rechazos, ejecuciones parciales, desconexión a mitad de orden y respuestas tardías. Invariante: nunca se duplica una orden ni se pierde el rastro de ninguna. Además, tests de contrato contra demo para detectar cambios de la API beta.

## 9. Arena (fase 4)

- **Agente** = `Strategy` + parámetros + cartera virtual + capital (100 €) + límites. Fila de configuración + estado en BD.
- **`SimulatedBroker`:** ejecuta a precios reales del día siguiente con el modelo de costes de la fase 2 (ADR-0022).
- **Job diario (orden fijo):** 1) ingesta y calidad; 2) ejecución simulada de las órdenes del día anterior; 3) valoración al cierre; 4) decisión de cada agente; 5) riesgo y registro de pendientes; 6) informe y alertas. Cada agente aislado: el fallo de uno no detiene al resto.
- **Controles:** benchmark buy-and-hold y N agentes aleatorios con la misma rotación y exposición que cada estrategia, semilla fija.
- **Vivo vs backtest:** cada semana se reejecuta el backtest sobre el periodo ya vivido y se compara la curva de patrimonio.
- **Informe:** HTML estático nocturno enviado por Telegram; Grafana opcional (ADR-0011).

## 10. Dinero real y fiscalidad (fase 5)

- Interruptor por agente, capital propio aislado, límites reducidos (mitad del tamaño, pérdida diaria menor), cuenta sin margen si es posible.
- Agente sombra en arena con la misma estrategia; se recalibran comisiones y slippage.
- Escalado: como máximo duplicar cada 3 meses, tras 3 meses sin incidentes y con tope fijado por el usuario (ADR-0008).
- **`TaxLedger`:** FIFO por ISIN; conversión a EUR al cambio de cada fecha; marca de pérdidas afectadas por la regla de los dos meses; CSV anual de ventas, dividendos y retenciones; aviso del modelo 720 a 40.000 € (umbral legal 50.000 € por categoría). Revisión por asesor antes de la primera declaración (ADR-0017). No es asesoramiento fiscal.
- **Runbook:** API caída, reconciliación fallida, kill switch. Backups diarios de PostgreSQL con restauración probada.

## 11. Operación, seguridad y configuración

- **Perfiles:** `dev` (local), `paper` (demo/arena), `live`. YAML por perfil, secretos por variables de entorno.
- **Claves de API:** una por entorno (demo, real), solo como secreto. La conexión a la API sale únicamente desde la máquina de despliegue.
- **Fallo de sesión (ADR-0010):** si la API no responde, devuelve 429 sostenido o rechaza la clave, el bot no opera y avisa.
- **Alertas:** errores, desconexiones, discrepancias y resumen diario por Telegram; heartbeat externo a 24 h.
- **Despliegue:** `docker compose up` con app + PostgreSQL en un VPS o mini PC Linux. Sin Kubernetes (ADR-0009).
- **Resiliencia tier 3:** backup y restauración, RPO/RTO de 24 h.

## 12. Matriz de riesgos y mitigaciones

| Riesgo | Mitigación técnica |
| --- | --- |
| Sobreajuste | Holdout, walk-forward, sensibilidad, `trial_log`, controles aleatorios |
| Sesgo de futuro | t/t+1, `available_from`, test de no anticipación |
| Costes subestimados | Modelo de costes completo; agente sombra recalibra |
| Impuestos ignorados | Métricas netas, `TaxLedger` con regla de los dos meses |
| Errores de ejecución | Máquina de estados, idempotencia, reconciliación, fat finger, tests de fallos |
| Caída o cambio de la API beta | Reintentos con espera, heartbeat, tests de contrato contra demo, runbook |
| Concentración oculta | Límites a nivel de cuenta |
| Datos defectuosos | Validaciones, doble fuente |
| Degradación | Apagado automático por caída, revisión periódica |
| Poca muestra | Criterios fijados de antemano, controles aleatorios |
| Seguridad | Secretos en env, API solo local, permisos mínimos |
| Dependencia de una persona | Documentación, runbook, configuración versionada |

## 13. Estrategia de pruebas

| Nivel | Qué cubre |
| --- | --- |
| Unitarias | Estrategias con series sintéticas, reglas de riesgo, `TaxLedger` (casos FIFO y dos meses) |
| Propiedad / no anticipación | Alterar datos futuros no cambia decisiones pasadas |
| Integración | Testcontainers con PostgreSQL real, migraciones Flyway, ingesta idempotente |
| Fallos | Broker falso con rechazos, parciales, cortes y respuestas tardías |
| Contrato | Cliente eToro contra demo (periódico) |
| Arena vs backtest | Comparación semanal de curvas |

## 14. Hoja de ruta de implementación

1. Cuenta eToro, clave demo, comprobar ETFs reales en el catálogo (fase 0).
2. Esqueleto Maven multimódulo, CI, Docker Compose con PostgreSQL, Flyway (fase 0).
3. Tabla `instrument` con 10–15 ISIN (fase 0).
4. Histórico de 2–3 ETFs para probar la tubería; prueba de fuentes de dos días (fase 1).
5. `Strategy` + backtest de la media de 200 días contra el benchmark: primer resultado en pocas semanas (fase 2).

## 15. Excepciones respecto a la fábrica (ai-project-factory)

Docker Compose en vez de Kubernetes (ADR-0009), solo PostgreSQL (ADR-0023), sin frontend, sin AWS/Terraform/Vault/Keycloak/Istio. Respuestas de intake: `notifications` sí (Telegram); `full_history` no (ledger inmutable); `resilience_tier` 3; `frontend` no; el resto de capacidades, no.

# etf-trading-bot

Bot de trading de ETFs UCITS de acumulación en EUR (Xetra) para eToro. Java 21, Spring Boot 3.5, PostgreSQL 16. Decisión diaria, solo largos, 100 € de capital inicial.

**Estado honesto:** el código de las fases 0 a 5 está casi todo escrito y probado con simulaciones, pero **ningún criterio de salida de fase está cumplido**. Falta la cuenta de eToro (clave demo), meses de funcionamiento y verificar la API real. Primer backtest con datos reales: ninguna de las 4 estrategias bate al benchmark ([resultado preliminar](docs/resultados-fase2-preliminar.md)). Nada de esto es asesoramiento financiero ni fiscal.

## Documentación

| Documento | Para qué |
| --- | --- |
| [docs/brief.md](docs/brief.md) | Origen: el encargo |
| [docs/PRD.md](docs/PRD.md) | Qué y por qué, fases y criterios de salida |
| [docs/TRD.md](docs/TRD.md) | Cómo: arquitectura técnica |
| [docs/adr/](docs/adr/README.md) | 24 decisiones de arquitectura |
| [docs/runbook.md](docs/runbook.md) | Operación: kill switch, incidencias, copias de seguridad |
| [docs/fase0-verificacion-etoro.md](docs/fase0-verificacion-etoro.md) | 13 puntos de la API de eToro **sin verificar** |
| [docs/resultados-fase2-preliminar.md](docs/resultados-fase2-preliminar.md) | Primer backtest con datos reales |

## Arranque

```bash
cp .env.example .env      # secretos locales, nunca en git
make up                   # PostgreSQL (docker) + app; la primera vez descarga el histórico de Yahoo
make down                 # para la base; los datos se conservan en el volumen pgdata
make reset                # BORRA los datos
make test                 # mvn verify (los tests de integración usan Docker/Testcontainers)
```

Los datos de mercado **no se guardan en git**: viven en PostgreSQL. El universo de desarrollo (`config/dev-universe.env`) tiene ISIN **sin verificar** contra eToro; `config/universe.yml` queda vacío hasta verificarlos.

Perfiles: `dev` (por defecto), `paper`, `live` (`SPRING_PROFILES_ACTIVE`). Secretos solo por variables de entorno.

## Módulos

Maven multimódulo. Las dependencias van siempre hacia `trading-core`; los módulos de dominio no conocen la base de datos ni a eToro.

| Módulo | Qué hace | Estado |
| --- | --- | --- |
| `trading-core` | Tipos compartidos y contratos: `Strategy`, `Broker` y sus excepciones, `OrderRequest`, `OrderPlanner`, `RiskRule`, `KillSwitch`, `Alerter`, `RateLimiter` | Hecho |
| `trading-data` | Calendario Xetra, lectura de CSV, validación de calidad, limpieza, hash del dataset, repositorio de velas, ingesta idempotente, fuentes `CsvDirectoryBarSource` y `YahooBarSource` | Hecho. Calendario Xetra **sin verificar** contra el oficial |
| `trading-strategies` | Buy and hold, tendencia, momentum con rotación, volatilidad objetivo, swing RSI, agente aleatorio de control | Hecho |
| `trading-backtest` | Motor (decide al cierre t, ejecuta en la apertura t+1), costes, métricas netas, walk-forward, holdout, sensibilidad ±20 %, informes | Hecho |
| `trading-risk` | `RiskManager` con la última palabra: lista blanca, peso máximo, exposición, pérdida diaria, drawdown por agente, liquidez, precio absurdo, duplicados, horario | Hecho |
| `trading-execution` | Máquina de estados de órdenes, persistencia antes de enviar, reconciliación que detiene el trading, `DryRunBroker`, `RealTradingGate` (dinero real por agente, cerrado por defecto) | Hecho. Sin conectar a un broker real |
| `trading-broker-sim` | `SimulatedBroker` (comparte lógica con el backtest) e inyector de fallos para tests | Hecho |
| `trading-broker-etoro` | Cliente de la API de eToro con límite de ritmo | Escrito **con suposiciones** sin verificar; probado solo contra un servidor local falso |
| `trading-arena` | Varios agentes compitiendo con capital virtual, controles, comparación vivo-vs-backtest, criterios de finalista, informe HTML | Hecho. Sin meses de datos reales |
| `trading-tax` | Libro fiscal FIFO, regla de los dos meses, CSV anual, alerta del modelo 720 | Hecho. **No es asesoramiento**; sin compensación de pérdidas entre años; requiere revisión de un experto |
| `trading-app` | Aplicación Spring: configuración, migraciones Flyway, repositorios JDBC, jobs diarios, alertas, kill switch, CLI de backtest | Hecho |

## Qué corre dentro de la aplicación

| Pieza | Activación | Descripción |
| --- | --- | --- |
| Ingesta diaria | `trading.ingestion.enabled=true` | Tras el cierre (19:00 Berlín) descarga y valida; alerta de incidencias críticas de los últimos 30 días |
| Job del arena | `trading.arena.enabled=true` | 19:30: repite el histórico, guarda el último día, envía resumen y escribe `reports/arena/arena-AAAA-MM-DD.html`. Usa `trading.arena.start-date` |
| Kill switch | siempre (en BD) | Frena todo el trading; endpoint en 127.0.0.1 con token (`KILL_SWITCH_TOKEN`, 16+ caracteres) |
| Telegram | `TELEGRAM_BOT_TOKEN` y `TELEGRAM_CHAT_ID` | Alertas, y comandos `/kill` y `/status` solo desde el chat configurado. Sin token, las alertas van al log |
| Bucle diario de trading (`TradingDayJob`) | Código y tests listos; **sin programar** | Por agente y día: sincroniza y reconcilia, lee datos, decide, el `RiskManager` juzga, envía, registra. El día se reclama de forma atómica (`trading_day`): repetirlo o lanzarlo en paralelo no duplica órdenes, y un día que quedó a medias no se reintenta solo. Se conectará al broker de la demo de eToro; con el simulado en memoria no se programa porque tras un reinicio la reconciliación vería una diferencia |
| Watchdog | siempre (cada 30 min) | Vigila lo que no genera eventos: datos obsoletos (más de 5 días), día de trading atascado o fallido, órdenes de resultado desconocido más de un día, kill switch activo más de un día, incidencias de datos. Avisa una vez al aparecer y otra al resolverse (sin spam) |
| Página de estado | `trading.monitoring.port` (8098; negativo la desactiva) | Solo lectura, solo en 127.0.0.1: `/status` (JSON) y `/metrics` (formato Prometheus, listo para Grafana o Azure Monitor). Sin importes ni secretos |
| Backtest por consola | `BacktestMain` | Con CSV por ISIN aplica los criterios de la fase 2 y escribe `summary.md` |

Copias de seguridad: `scripts/backup.sh` y `scripts/restore-check.sh` (ver [runbook](docs/runbook.md)).

## Estado por fase

| Fase | Criterio de salida (resumen) | Estado |
| --- | --- | --- |
| 0 Fundamentos | Demo: orden, catálogo y cartera guardados en BD | Bloqueada: falta cuenta y clave demo |
| 1 Datos | 10+ años limpios, 2 semanas de ingesta automática | Parcial: 4 ETF cargados; faltan las 2 semanas, 2ª fuente y calendario oficial |
| 2 Backtest | 1+ estrategia bate al benchmark en 70 % de ventanas | Resultado negativo preliminar |
| 3 Riesgo y ejecución | 4 semanas en demo sin intervención | Código y bucle diario listos y probados de extremo a extremo con broker simulado; falta la cuenta |
| 4 Arena | 1+ finalista con los 5 criterios | Sin arrancar; necesita meses |
| 5 Dinero real | 3 meses sin incidentes | No procede aún |
| 6 Capas avanzadas | Mejora neta demostrada | Abierta |

## Infraestructura (opcional)

`infra/terraform/` es un esqueleto para practicar Azure (PostgreSQL gestionado, Key Vault, Log Analytics). Pasa `terraform validate` pero **nunca se ha aplicado**. El bot no lo necesita: corre en local con `make up`. Ver [infra/terraform/README.md](infra/terraform/README.md).

## Convención de trabajo

Ramas cortas, PR con CI en verde y squash. **Cada PR que cambie el comportamiento actualiza este README** (módulos, estado, configuración).

## Pruebas

`make test` ejecuta todo (JaCoCo genera `target/site/jacoco` por módulo). Además de los tests unitarios hay: concurrencia e integridad sobre PostgreSQL real (`PersistenceIntegrityTest`), bucle diario de extremo a extremo (`TradingDayJobTest`), robustez de estrategias con datos raros y servidores falsos para eToro, Yahoo y Telegram. Estos tests encontraron y corrigieron errores reales (fills duplicados bajo concurrencia, estados de orden contradictorios, ingesta paralela).

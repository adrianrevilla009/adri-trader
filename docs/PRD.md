# PRD: Bot de trading de ETFs en Java (`etf-trading-bot`)

- **Estado:** borrador para Gate 1 (plan)
- **Fecha:** 2026-11-19
- **Autor:** Adrián
- **Origen:** [brief.md](brief.md)
- **Documentos relacionados:** [TRD](TRD.md) · [ADR](adr/README.md)

> Convención del proyecto: documentación en español, código e identificadores en inglés. Lo que no se ha verificado se marca **«no comprobado»**.

## 1. Resumen

Un bot personal que, una vez al día tras el cierre de Xetra, decide con estrategias sistemáticas qué ETFs UCITS de acumulación mantener, y opera en eToro. Una única cadena (datos → estrategia → riesgo → broker) sirve igual para backtest, arena de simulación y dinero real.

**El objetivo es validar el sistema, no obtener rentabilidad apreciable**: el capital es de 100 €. Un resultado válido del proyecto incluye concluir que no hay estrategia que supere a mantener un ETF global.

## 2. Problema y motivación

- Probar si estrategias simples y baratas (tendencia, momentum, volatilidad objetivo, swing) baten a un benchmark pasivo, neto de costes e impuestos, con un método que evite engañarse (sobreajuste, sesgo de futuro, poca muestra).
- Construir un sistema operativo fiable (órdenes sin duplicar ni perder, reconciliación, kill switch) antes de arriesgar un euro real.
- Aprender y documentar el proceso completo con criterios de salida medibles por fase.

## 3. Objetivos y no objetivos

### Objetivos (v1)

1. Tubería de datos diaria reproducible, con libro de operaciones desde el día uno.
2. Motor de backtest a nivel de cartera con modelo de costes y validación estadística explícita.
3. Gestor de riesgo independiente con kill switch, con la última palabra sobre cualquier orden.
4. Capa de ejecución robusta contra la API beta de eToro (demo y real).
5. Arena de agentes con simulador propio, agentes de control y criterios de promoción fijados de antemano.
6. Paso a real con un solo agente y 100 €, escalado solo por evidencia.
7. Módulo fiscal español (`TaxLedger`): FIFO, regla de los dos meses, informe anual.

### No objetivos (fuera de la v1)

- Acciones individuales, divisas distintas del euro, posiciones cortas, apalancamiento.
- Alta frecuencia o intradía.
- Noticias con LLM, ML sobre precios, meta-agente (se tratan en la fase 6, tras la fase 5).
- Gestión de dinero de terceros (actividad regulada).
- Frontend web, multiusuario, cloud, Kubernetes.
- Asesoramiento fiscal (el módulo fiscal lo revisa un asesor).

## 4. Usuario y supuestos

- **Usuario único:** el propio desarrollador, a tiempo parcial, Java como lenguaje principal.
- **Horizonte:** diario o semanal. Decisión tras el cierre de Xetra; ejecución en t+1 poco después de la apertura.
- **Universo v1:** 10–15 ETFs UCITS de acumulación en euros, cotizados en Xetra, con ≥12 años de historia.
- **Capital:** 100 € simulados por agente; 100 € reales con un agente en la fase 5.
- **Broker:** eToro (API pública beta), 0 € de comisión en ETFs reales.

## 5. Requisitos funcionales

| ID | Requisito | Fase |
| --- | --- | --- |
| RF-01 | Conectar con la API de eToro en demo: leer catálogo y cartera, colocar y cerrar una orden de prueba | 0 |
| RF-02 | Identificar instrumentos por ISIN + bolsa, con mapeo al identificador de eToro | 0–1 |
| RF-03 | Ingesta diaria idempotente de barras, con reintentos y alerta por Telegram | 1 |
| RF-04 | Separar dato crudo (`bar_daily_raw`) de serie limpia (`bar_daily`), regenerable | 1 |
| RF-05 | Validaciones de calidad automáticas y registro de incidencias | 1 |
| RF-06 | Guardar la fecha de disponibilidad de cada dato y la versión/hash del dataset | 1–2 |
| RF-07 | Libro de operaciones (`order`, `fill`, `cash_ledger`) inmutable | 1 |
| RF-08 | Interfaz única `Strategy.decide(MarketSnapshot, PortfolioState) → TargetPortfolio` | 2 |
| RF-09 | Motor de backtest: decisión en t, ejecución en t+1 con costes y slippage configurables | 2 |
| RF-10 | Cuatro estrategias iniciales: tendencia, momentum con rotación, volatilidad objetivo, swing | 2 |
| RF-11 | Validación: holdout, walk-forward, sensibilidad, registro de intentos, bootstrap, test de no anticipación | 2 |
| RF-12 | Métricas netas (CAGR, vol, Sharpe, Sortino, MaxDD, Calmar, rotación, alfa, beta, TE) e informe HTML/CSV reproducible | 2 |
| RF-13 | `RiskManager` con reglas configurables que recortan, rechazan, aplazan o apagan | 3 |
| RF-14 | Kill switch (Telegram, flag en BD, endpoint protegido) | 3 |
| RF-15 | Máquina de estados de orden persistida antes de enviar; sin reenvío a ciegas | 3 |
| RF-16 | Reconciliación al arrancar y tras operar; detiene la operativa si hay diferencia | 3 |
| RF-17 | Modo dry-run | 3 |
| RF-18 | `SimulatedBroker` con la misma interfaz `Broker` que `EToroBroker` | 4 |
| RF-19 | Orquestador de arena con aislamiento por agente, agentes de control (benchmark y aleatorios con semilla) | 4 |
| RF-20 | Comparación semanal vivo vs backtest; informe HTML nocturno por Telegram | 4 |
| RF-21 | Ciclo de vida del agente: `CANDIDATO → ARENA → FINALISTA → REAL_PEQUEÑO → RETIRADO` | 4–5 |
| RF-22 | Activación por agente en real con capital y límites propios; agente sombra | 5 |
| RF-23 | `TaxLedger`: FIFO por ISIN, regla de los dos meses, CSV anual, aviso modelo 720 a 40.000 € | 5 |
| RF-24 | Runbook, backups diarios de PostgreSQL con restauración probada | 5 |

## 6. Requisitos no funcionales

- **Corrección monetaria:** `BigDecimal` para dinero y cantidades; `double` solo en estadística.
- **Reproducibilidad:** cada backtest guarda configuración, hash de datos, commit de Git e informe.
- **Determinismo:** `Clock` inyectable, UTC en BD, calendario por bolsa.
- **Seguridad:** secretos solo por variables de entorno; nada sensible en el repositorio; salida HTTPS únicamente.
- **Resiliencia (tier 3):** backup y restauración con RPO/RTO de 24 h; heartbeat externo si el bot calla 24 h.
- **Auditabilidad:** registro de decisiones de cada agente y de cada orden (exigido por los términos de eToro).
- **Cumplimiento de la API de eToro:** 60 lecturas y 20 escrituras por minuto y clave; limitación propia de ritmo, tope de frecuencia, reintentos con espera, anulación manual.
- **Observabilidad:** logs estructurados (Logback) y alertas por Telegram.
- **Mantenibilidad:** sin Lombok; Maven multimódulo; tests con JUnit 5, AssertJ y Testcontainers.

## 7. Plan por fases y criterios de salida

| Fase | Contenido | Criterio de salida (medible) |
| --- | --- | --- |
| **0. Fundamentos** | Cuenta eToro, claves demo, universo, benchmark, reglas de riesgo en YAML, esqueleto Maven/CI/Flyway | Programa de prueba conecta a demo, coloca y cierra una orden, lee catálogo y cartera y los guarda en BD; universo y reglas versionados |
| **1. Datos** | Ingesta, calidad, ledger, modelo de datos | Histórico limpio (≥10 años o máximo disponible); 2 semanas de ingesta automática sin intervención; informe de calidad sin errores críticos; ledger funcionando |
| **2. Backtest y estrategias** | Motor, costes, 4 estrategias, validación | ≥1 estrategia que, neta de costes, supere al benchmark en Sharpe o Calmar en ≥70 % de ventanas walk-forward, aguante ±20 % de parámetros y tenga ≥30 operaciones si es swing. Si no, se documenta y se descarta la familia |
| **3. Riesgo y ejecución** | `RiskManager`, kill switch, `EToroBroker`, reconciliación | 4 semanas en demo sin intervención, reconciliación sin diferencias sin explicar, tests de fallos en verde, kill switch probado en ensayo real |
| **4. Arena** | `SimulatedBroker`, orquestador, controles, panel | ≥1 finalista que cumpla los 5 criterios, o conclusión documentada de que ninguno y vuelta a la fase 2. **Gate 4: puerta al dinero real** |
| **5. Dinero real, impuestos** | Real escalonado, agente sombra, `TaxLedger` | ≥3 meses con dinero real sin incidentes, tracking aceptable frente al gemelo, informe fiscal anual contrastado con el broker |
| **6. Capas avanzadas** | Régimen, macro, meta-agente, noticias LLM, ML | Por capa: mejora neta frente a la variante sin ella en walk-forward y ≥3 meses de arena en paralelo |

Estimación orientativa de las fases 0–3: 3–5 meses a tiempo parcial. La fase 4 es de espera (≥6 meses para juzgar resultados). La fase 6 queda abierta.

### Criterios de promoción a finalista (fijados antes de empezar)

1. ≥3 meses de arena para fallos técnicos y ≥6 para juzgar resultados.
2. Mismas decisiones que el backtest en ≥95 % de los días y diferencia de patrimonio ≤0,5 % del capital.
3. Sharpe o Calmar neto ≥ benchmark.
4. Caída máxima dentro del límite de riesgo.
5. Resultado por encima del percentil 90 de los agentes aleatorios.

## 8. Métricas de éxito del proyecto

- Fases cerradas con su criterio de salida cumplido, sin relajar umbrales a posteriori.
- Cero órdenes duplicadas o sin rastro; cero diferencias de reconciliación sin explicar.
- Desviación vivo-vs-backtest dentro de tolerancia.
- Coste total (spread + slippage + impuestos) cuantificado y comparado con el modelo.
- Una conclusión documentada: o un agente demuestra ventaja neta, o se concluye que lo razonable es mantener un ETF global.

## 9. Riesgos y criterios de parada

Riesgos principales: sobreajuste, sesgo de futuro, costes subestimados, impuestos ignorados, errores de ejecución, cambios en la API beta de eToro, concentración oculta entre agentes, datos defectuosos, degradación, poca muestra, seguridad y dependencia de una persona. El detalle de mitigaciones está en el [TRD §12](TRD.md).

**Se frena o se detiene cuando:**

1. Ninguna estrategia cumple el criterio de la fase 2.
2. En la arena, el vivo diverge de forma sostenida del backtest sin explicación.
3. En real, un agente supera el 15 % de caída desde máximos: se apaga y no se reactiva sin revisión humana.
4. Hay una discrepancia de reconciliación sin causa conocida: se detiene toda la operativa.
5. Costes e impuestos se comen la ventaja: el agente se retira.

Solo se arriesga capital que se pueda perder sin afectar a la vida diaria; el capital sube por evidencia, no por impaciencia.

## 10. Dependencias, supuestos no comprobados y preguntas abiertas

**No comprobado (se verifica en la fase indicada):**

| Tema | Fase |
| --- | --- |
| Que verificar la cuenta y generar la clave demo no exijan depósito | 0 |
| Que la API real esté habilitada para cuentas minoristas españolas | 0 |
| Qué ETFs del universo figuran como instrumentos reales (no CFD) en el catálogo | 0 |
| Profundidad histórica de las velas de eToro | 1 |
| Que eToro acepte órdenes en euros; mínimo por orden (~10 $) | 0 |
| Que las órdenes reales funcionen igual que en demo | 0 / 5 |
| Exportación del estado de cuenta por API | 5 |
| Qué módulos de la fábrica pueden desactivarse; si el Gate 5 admite excepción | `/capability-select` |

**Decisiones abiertas con ADR pendiente:** fuente de datos históricos (prueba de dos días en la fase 1; ADR-0005), lista final de ETFs, tope de capital en el escalado, ajuste de límites de riesgo (solo con ADR).

## 11. Encaje con ai-project-factory

Este PRD y el brief son la entrada del intake (Gate 0) y del plan (Gate 1). Fases 2 y 3 por módulo con su PR (Gates 2 y 3); Gate 4 (release) = puerta al dinero real. Excepciones a registrar: Docker Compose en lugar de Kubernetes, solo PostgreSQL, sin frontend, sin AWS/Terraform/Vault/Keycloak/Istio.

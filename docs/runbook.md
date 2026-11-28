# Manual de operación (borrador)

Qué hacer cuando algo falla. Se completa y se ensaya antes de poner dinero real (fase 5). Los comandos concretos del servidor dependen del despliegue, que aún no existe.

## Principios

- Ante la duda, **el bot no opera**. El kill switch bloquea órdenes nuevas; no liquida posiciones, eso lo decide una persona.
- Nada se «arregla» solo: una discrepancia se investiga, se anota y se reactiva a mano.
- Una orden nunca se reenvía sin consultar antes su estado en el broker.

## Kill switch

- **Estado:** tabla `kill_switch` (una fila). `active = TRUE` bloquea todo envío nuevo.
- **Activar a mano:** `UPDATE kill_switch SET active = TRUE, reason = '<motivo>', changed_at = now() WHERE id = 1;`
- **Desactivar (solo tras revisar la causa):** `UPDATE kill_switch SET active = FALSE, reason = NULL, changed_at = now() WHERE id = 1;`
- Lo activa solo el bot ante: orden desconocida para el broker, diferencia de reconciliación, acceso denegado por la API.
- **Endpoint protegido** (solo en 127.0.0.1, puerto 8099, token en `KILL_SWITCH_TOKEN` de 16+ caracteres; sin token no se levanta): `curl -X POST -H "Authorization: Bearer $KILL_SWITCH_TOKEN" "http://127.0.0.1:8099/killswitch/activate?reason=motivo"`; también `/killswitch/deactivate` y `GET /killswitch`.
- Pendiente: comando de Telegram, y ensayo real antes del dinero real.

## Situaciones

| Situación | Señal | Qué hacer |
| --- | --- | --- |
| La API de eToro no responde | Alertas de lectura fallida; órdenes en estado `SUBMITTED` con nota `UNKNOWN` | No reenviar. Esperar a que vuelva y dejar que `syncOpenOrders` resuelva. Si hay órdenes desconocidas, el bot se detiene solo |
| Clave rechazada (401/403) | Alerta `Broker denied access`; kill switch activo | Revisar la clave en eToro (puede estar revocada o caducada). No reintentar en bucle: eToro puede suspender el acceso por tráfico anómalo |
| 429 sostenido | Esperas largas, escrituras rechazadas por límite | Reducir frecuencia; comprobar que no hay más de un proceso usando la misma clave |
| Diferencia de reconciliación | Alerta `Reconciliation difference`; kill switch activo | Comparar la cartera de eToro con la tabla `fill`. Averiguar la causa (ejecución parcial no sincronizada, orden manual, error). Corregir con un asiento compensatorio, nunca editando filas. Reactivar tras explicarla |
| Orden desconocida para el broker | Alerta `unknown to the broker; not resending` | Mirar en eToro si existe. Si no existe, marcarla `CANCELLED` a mano con una nota. Si existe, enlazarla en `trade_order.broker_order_id` |
| Un agente supera el 15 % de caída | Alerta `Agent ... shut down`; estado `RETIRED` | No se reactiva sin revisión humana. Decidir si se liquida la posición |
| Datos de la ingesta con errores críticos | Alerta `Critical data issues`; filas abiertas en `data_quality_issue` | No decidir con datos dudosos. Revisar la fuente, corregir o excluir el día, marcar la incidencia como resuelta |
| El bot deja de reportar 24 h | Latido externo (`HEARTBEAT_URL`; ver observability.md) | Entrar al servidor, revisar logs y `docker compose ps` |

## Monitorización y alertas

- Las alertas salen por Telegram (o al log si no hay token) con niveles INFO, WARNING y CRITICAL.
- El watchdog (cada 30 min) avisa de: datos obsoletos, día de trading atascado o fallido, órdenes de resultado desconocido, kill switch activo más de 24 h e incidencias de datos. Cada problema avisa una vez y luego envía "Resolved".
- Estado de un vistazo: el dashboard de Grafana (http://127.0.0.1:3000) o, con Compose, `docker compose exec app wget -qO- http://127.0.0.1:8098/status`. Métricas, alertas y trazas: [observability.md](observability.md).
- Un día "atascado" (STARTED sin terminar) **no se reintenta solo**: puede haber órdenes enviadas. Comprobar en eToro y en la tabla `trade_order`, y después corregir la fila de `trading_day` a mano.

## Qué hacer ante cada alerta

Las alertas salen de `ops/prometheus/rules/trading.yml` y llegan por Telegram. Gravedad: **page** (te avisa ya y se repite cada hora), **ticket** (se agrupa y se repite cada 24 h; mirar durante el día) e **info** (nunca se envía). Cada una enlaza aquí.

| Alerta | Gravedad | Qué significa | Qué hacer |
| --- | --- | --- | --- |
| `BotDown` | page | Prometheus no puede leer el bot en 5 min | `docker compose ps` y `docker compose logs app`. Si no arranca, mirar la base de datos y el último despliegue. No reiniciar a ciegas si había un día de trading a medias (ver `TradingDayStuck`) |
| `DatabaseUnreachableFromBot` | page | El bot no puede leer su base de datos | `docker compose ps postgres`, espacio en disco, logs de PostgreSQL. Con la base caída el bot no decide ni envía |
| `KillSwitchActive` | page | Hay un kill switch activo: no se envían órdenes | Averiguar por qué se activó (`GET /killswitch` da el motivo) y desactivarlo solo después |
| `TradingDayStuck` | page | Un día quedó en STARTED más de una hora | **No se reintenta solo**: puede haber órdenes enviadas. Comprobar en eToro y en `trade_order`, luego corregir la fila de `trading_day` a mano |
| `TradingDayFailed` | page | Un día terminó FAILED | Leer `trading_day.detail` y el log. Corregir la causa antes del siguiente día |
| `OrderOutcomeUnknown` | page | Una orden lleva más de un día sin resultado conocido | Mirar en eToro si existe. Si no, marcarla `CANCELLED` con nota; si existe, enlazarla en `trade_order.broker_order_id` |
| `AgentDrawdownLimit` | page | Un agente pasó el 15 % de caída | El gestor de riesgo lo retira (`RETIRED`). No se reactiva sin revisión; decidir si se liquida la posición |
| `NegativeCash` | page | Un agente tiene caja negativa | Error contable o fill sin reconciliar. El libro es de solo añadir: investigar antes de corregir con un ajuste |
| `BackupMissing` | page | Pasaron 30 h sin una copia verificada | `docker compose logs backup`; probar `scripts/backup.sh`. Revisar el apartado de copias de seguridad |
| `AgentDrawdownWarning` | ticket | Un agente cayó entre el 10 % y el 15 % | Aviso temprano; nada que hacer aún |
| `AgentEquityDroppedInADay` | ticket | Un agente perdió más del 5 % en un día | Comparar con el benchmark: ¿mercado, dato erróneo o fallo de la estrategia? |
| `OrdersRejected` | ticket | El broker rechazó órdenes en el último día | Ver `trade_order.note`: id de instrumento, tamaño o sesión |
| `MarketDataStale` / `NoMarketData` | ticket | Datos obsoletos o inexistentes | Logs de la ingesta; la fuente pudo cambiar o limitar. Los agentes no deben decidir con datos viejos |
| `CriticalDataIssues` | ticket | Incidencias críticas de calidad de datos | Revisar `data_quality_issue`, corregir o excluir el día y marcarla resuelta |
| `ArenaNotRunning` | ticket | La arena no registra días | Log del job de las 19:30; suele ser consecuencia de datos que faltan |
| `MetricsRefreshFailing` | ticket | La lectura de métricas falla repetidamente | Base de datos lenta o inalcanzable; los paneles muestran valores viejos |
| `JvmHeapHigh`, `DatabasePoolWaiting`, `ScheduledJobFailing` | ticket | Síntomas de recursos | Panel de Grafana «Runtime» y log de la aplicación |
| `BotRestartedRecently` | info | El bot reinició hace menos de 5 min | Normal tras un despliegue; si no lo fue, mirar el log (sin memoria, el proceso sale a propósito) |
| `Watchdog` | info | Latido siempre activo | Si el servicio externo deja de recibirlo, la máquina o Alertmanager están caídos |

## Copias de seguridad

- Con Compose el servicio `backup` lo hace solo cada noche (03:15 UTC, últimas 14, volumen `backups`) y la alerta `BackupMissing` salta si pasan 30 h sin una copia verificada. **Copiar el volumen fuera de la máquina sigue pendiente.**
- `scripts/backup.sh [carpeta] [n]` hace un `pg_dump` comprimido, verifica que se pueda leer y conserva los últimos n (14). Programarlo a diario (cron 15 3 * * *) y copiar la carpeta a otro disco o máquina.
- `scripts/restore-check.sh <dump>` lo restaura en una base temporal y muestra el número de filas de las tablas clave.
- **Restauración probada** antes del dinero real: restaurar en una base vacía y comprobar que el libro de operaciones cuadra con eToro.
- Objetivo de recuperación: 24 horas (resiliencia tier 3).

## Ciclo diario en la cuenta demo de eToro

Decisión y límites: [ADR-0028](adr/0028-ciclo-diario-demo-etoro.md). Solo demo, apagado por defecto.

**Encender** (en `.env` o en el entorno; las claves nunca en el repositorio):

```
ETORO_API_KEY=...                 # clave DEMO
ETORO_USER_KEY=...
TRADING_LIVE_ENABLED=true
TRADING_LIVE_ISIN=IE00B4L5Y983    # y su id en TRADING_ETORO_INSTRUMENTS (config/dev-universe.env ya los trae)
```

Corre de lunes a viernes a las 19:15 (Europe/Berlin), después de la ingesta. Cada orden está limitada a 10 y el agente ve como
máximo 40 de efectivo (`trading.etoro.max-order-amount`, `trading.live.capital`). Con `trading.etoro.mode=REAL` la aplicación no arranca.

**Probarlo ya, sin esperar a las 19:15:** `TRADING_LIVE_RUN_ON_STARTUP_DAY=2026-11-25` ejecuta ese día una vez al arrancar, después de
la ingesta. Usa un día que tenga barra; el día se reclama en la base de datos, así que no se repite.

**Qué mirar** (log y Grafana): `Live day <fecha>: TRADED (sent N, rejected N, unknown N)`. Otros resultados:

| Resultado | Significa | Qué hacer |
| --- | --- | --- |
| `NO_DATA` | Falta la barra de ese día (Yahoo a veces no la trae); el bot no opera | Esperar a la ingesta del día siguiente o cargarla; no forzar |
| `HALTED` | La reconciliación encontró diferencia con eToro, o se activó el interruptor | Ver «Qué hacer ante cada alerta»; no reanudar sin entender la diferencia |
| `ALREADY_RAN` | Ese día ya se reclamó | Nada. Si quedó `STARTED` sin terminar hay una alerta crítica: revisar a mano, no se reintenta |
| `FAILED` | Excepción durante el día | Leer la alerta; las órdenes pudieron salir |

**Apagar:** `TRADING_LIVE_ENABLED=false` y reiniciar, o el interruptor (kill switch) para parar al instante.

**Comprobaciones contra eToro, con tope 10 y solo demo:** `ETORO_LIVE_DEMO=1 mvn -pl trading-broker-etoro -am test -Dtest=EToroDemoLiveTest`.

**Límites conocidos** (no los des por resueltos): la estrategia es `buy-and-hold`, no hay ninguna que supere al benchmark; USD y EUR se
tratan 1:1; una orden de mercado fuera de horario no está probada; eToro acepta una orden menor de 10 USD y la rechaza después.

## Agentes desde la pantalla (varios agentes en la cuenta demo)

Decisión: [ADR-0030](adr/0030-agentes-dinamicos-api-ui.md). Los agentes se guardan en la base de datos; se crean, cambian, paran y
reanudan desde una pantalla (React, carpeta `frontend/`) que usa una API local. Solo demo.

**Encender la API** (en `.env`; sin token la API no existe):

```
TRADING_API_TOKEN=...             # 16 caracteres o más; es la contraseña de la pantalla
```

La API escucha en `127.0.0.1:8100` (en compose se publica solo en el loopback del equipo). **Pantalla:** `make ui-install` una vez y
`make ui` (http://127.0.0.1:5173); pide el token y lo guarda solo en esa pestaña.

**Cómo funciona cada día:** a las 19:15 corre cada agente *activo*, cada uno con su estrategia, su capital y sus instrumentos. Un
agente nuevo opera desde el **siguiente** ciclo. Un fallo en un agente se avisa y los demás siguen.

**Reglas que aplica el servidor (la pantalla solo las enseña):**

| Regla | Valor |
| --- | --- |
| Capital mínimo por agente | 40 (el 25 % por activo debe llegar al mínimo por orden de 10) |
| Tope por orden en demo | 10 (`trading.etoro.max-order-amount`); no se salta desde la API |
| Instrumentos | solo los que tienen id de eToro en `TRADING_ETORO_INSTRUMENTS` |
| Parar un agente | deja de enviar órdenes **y conserva sus posiciones**; cerrarlas es una acción aparte (aún no existe en la pantalla) |
| Pasos de escalado | los guarda el agente; subir de paso lo confirma una persona (el capital no cambia solo) |
| Modo PROD | apagado por defecto; ver la sección «Cambiar a PROD (MFA)» de abajo. Aun así no hay bróker real: en PROD el ciclo no opera |

**Cuenta compartida:** todos los agentes comparten la cuenta de eToro, así que la reconciliación compara la **suma** de los libros con
la cuenta. Cada agente ve solo su presupuesto: su capital menos lo que ya tiene invertido o pendiente de comprar.

**Cada cambio queda registrado** (quién, cuándo, valor anterior y nuevo) en `agent_config_audit`, que no admite `UPDATE` ni `DELETE`;
en la pantalla, botón «Historial». Primer agente: mientras no haya ninguno, `TRADING_LIVE_ISIN` crea `live-demo` (buy-and-hold, capital 40).

**No verificado:** que eToro acepte varios agentes comprando el mismo ETF el mismo día sin chocar con sus reglas de ETF parecidos;
el límite de 20 escrituras por minuto con muchos agentes.

## Antes del dinero real (lista de comprobación)

- [ ] Cuatro semanas en demo sin intervención y reconciliación sin diferencias sin explicar.
- [ ] Tests de fallos en verde (ya en CI).
- [ ] Kill switch ensayado de verdad (activar con una orden abierta y comprobar que nada nuevo sale).
- [ ] Restauración de copia de seguridad probada.
- [ ] Verificada la lista de [`fase0-verificacion-etoro.md`](fase0-verificacion-etoro.md).
- [ ] Gate 4 firmado con los criterios de la fase 4.
- [ ] Tope de escalado definido por la persona responsable (ADR-0008).

## Cambiar a PROD (MFA)

Decisión: [ADR-0031](adr/0031-cambio-a-prod-con-mfa.md). **No existe bróker real**: pasar a PROD no permite operar con dinero real;
solo prueba el interruptor, y mientras el modo sea PROD el ciclo diario **no opera** y avisa. Apagado por defecto.

**Encenderlo** (solo si quieres probar el proceso):

1. `bash scripts/new-mfa-secret.sh` imprime un secreto nuevo. Añádelo a una app de autenticación («introducir clave de configuración»,
   basada en tiempo, 6 cifras, 30 s) y pon en `.env`: `TRADING_PROD_ENABLED=true` y `TRADING_PROD_MFA_SECRET=<el secreto>`.
2. Reinicia el bot. En la pantalla aparece el botón «Cambiar a PROD…».

**Proceso en la pantalla:** (1) marcar las tres casillas y «Pedir confirmación»; (2) escribir el código de la app y la frase
`SWITCH TO PROD`; (3) «Sí, cambiar a PROD ahora». Todo el proceso debe hacerse antes de que caduque el desafío (120 s).

| Situación | Resultado |
| --- | --- |
| Función apagada | 409; queda registrado y avisa |
| Código, frase o desafío incorrectos o caducados | 403 siempre igual; se gasta el desafío; hay que empezar de nuevo |
| 3 fallos seguidos | 423: bloqueado 15 minutos; aviso CRITICAL |
| Mismo código dos veces | rechazado (anti-repetición) |
| Falla el registro | no se cambia el modo |
| Reinicio del bot | vuelve a DEMO |

**Volver a DEMO:** botón «Volver a DEMO» (sin MFA). **Quién hizo qué:** tabla `mode_audit` (no admite `UPDATE` ni `DELETE`).

**No verificado:** la entrega real de los avisos a Telegram; el flujo con una app de autenticación real (probado con códigos TOTP
calculados en las pruebas y con los vectores del RFC 6238); la pantalla en un navegador real.

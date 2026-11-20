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

## Antes del dinero real (lista de comprobación)

- [ ] Cuatro semanas en demo sin intervención y reconciliación sin diferencias sin explicar.
- [ ] Tests de fallos en verde (ya en CI).
- [ ] Kill switch ensayado de verdad (activar con una orden abierta y comprobar que nada nuevo sale).
- [ ] Restauración de copia de seguridad probada.
- [ ] Verificada la lista de [`fase0-verificacion-etoro.md`](fase0-verificacion-etoro.md).
- [ ] Gate 4 firmado con los criterios de la fase 4.
- [ ] Tope de escalado definido por la persona responsable (ADR-0008).

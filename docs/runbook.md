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
| El bot deja de reportar 24 h | Latido externo (pendiente de montar) | Entrar al servidor, revisar logs y `docker compose ps` |

## Copias de seguridad (pendiente de implementar)

- Copia diaria de PostgreSQL (`pg_dump`) a otro disco o máquina.
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

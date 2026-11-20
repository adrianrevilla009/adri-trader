# Despliegue y observabilidad

Todo el sistema arranca con `make up` (Docker Compose, una sola máquina). No hay Kubernetes a propósito: el bot es **un
solo proceso** (la aplicación Spring Boot) más PostgreSQL, y debe haber **exactamente una instancia** (el reclamo atómico del
día y el escritor único lo asumen). Un orquestador no aporta nada con un único proceso que no escala.

## Qué arranca

| Servicio | Perfil | Para qué | Puerto en el equipo |
| --- | --- | --- | --- |
| `postgres` | siempre | Base de datos (volumen `pgdata`) | ninguno |
| `app` | siempre | El bot: ingesta, arena, watchdog, página de estado y métricas | ninguno |
| `backup` | siempre | Copia nocturna verificada (03:15 UTC, últimas 14) y una al arrancar | ninguno |
| `prometheus` | observability | Guarda métricas 90 días y evalúa las reglas de alerta | ninguno |
| `alertmanager` | observability | Agrupa alertas y las envía a Telegram | ninguno |
| `otel-collector` | observability | Recibe trazas por OTLP y las reenvía a Tempo | ninguno |
| `tempo` | observability | Guarda trazas 14 días | ninguno |
| `grafana` | observability | Dashboards | `127.0.0.1:3000` |

`make up` arranca todo; `make up-core` solo base de datos, bot y copias. **Lo único publicado en el equipo es Grafana, y solo
en 127.0.0.1.** En un servidor se accede con un túnel SSH (`ssh -L 3000:127.0.0.1:3000 usuario@servidor`). La base de datos
no se publica: se entra con `make psql`.

Endurecimiento de los contenedores: sin privilegios nuevos, todas las capacidades de Linux retiradas (salvo las mínimas que
necesita el arranque de PostgreSQL), sistema de ficheros de solo lectura en el bot y en las copias, límites de memoria,
rotación de logs (10 MB × 5), reinicio automático, imágenes fijadas por digest.

## Primer arranque

```bash
make init        # crea .env con contraseñas aleatorias (nunca lo sobrescribe)
# edita .env: TELEGRAM_BOT_TOKEN y TELEGRAM_CHAT_ID (alertas), y más adelante las claves de eToro
make up
```

Si ya tenías el volumen `pgdata` de la versión anterior (contraseña `trading`), la contraseña nueva no coincidirá: `make reset`
(borra datos; se vuelven a descargar de Yahoo) o cambia la contraseña dentro de PostgreSQL.

## Métricas

La aplicación publica `/metrics` en el puerto 8098 **dentro de la red privada de Compose** (en el contenedor escucha en
0.0.0.0 pero el puerto no se publica; fuera de Docker sigue siendo solo 127.0.0.1). Prometheus lo recoge cada 30 s.

Las métricas de negocio **se leen de la base de datos** (la fuente de verdad) cada 30 s y se guardan en caché: una consulta
lenta o una base caída no bloquea nunca un scrape, y `trading_metrics_refresh_age_seconds` crece para que salte la alerta.
Antes de la primera lectura valen `NaN`, no un cero falso («0 órdenes, kill switch apagado» sería mentira).

| Métrica | Qué es |
| --- | --- |
| `trading_agent_equity_eur{agent}` | Último patrimonio del agente del arena |
| `trading_agent_drawdown_ratio{agent}` | 1 − patrimonio / máximo histórico (el agente se retira al 15 %) |
| `trading_agent_status{agent,status}` | Estado del ciclo de vida del agente |
| `trading_cash_eur{agent}` | Caja según el libro mayor |
| `trading_invested_cost_eur{agent}` | Comprado menos vendido, **a coste** (no a valor de mercado) |
| `trading_orders{agent,state}` | Órdenes por estado |
| `trading_fills`, `trading_fill_notional_eur`, `trading_fees_eur` | Ejecuciones, importe operado y comisiones, acumulados |
| `trading_kill_switch_active`, `trading_open_orders`, `trading_unknown_orders_stale`, `trading_days_stuck`, `trading_days_failed_3d`, `trading_data_issues_critical_30d` | Estado operativo (las mismas comprobaciones del watchdog) |
| `trading_data_age_days`, `trading_arena_age_days`, `trading_last_trading_day_age_days` | Antigüedad de los datos y de los jobs (−1 = nunca) |
| `trading_backup_age_seconds` | Antigüedad de la última copia verificada (`NaN` si no hay ninguna) |
| `trading_metrics_refresh_*` | Salud de la propia lectura de métricas |
| `jvm_*`, `hikaricp_*`, `process_*`, `tasks_scheduled_execution_*` | JVM, pool de conexiones y duración de cada job programado |

**Importes:** `/metrics` muestra patrimonio, caja y comisiones, así que solo debe verse en la red privada. `/status` sigue sin
importes. Si algún día se publica algo fuera de la máquina, hay que filtrar esas métricas antes.

## Dashboard

`ops/grafana/dashboards/trading.json` («Trading overview») se carga solo (provisioning, no editable desde la interfaz: se
cambia en el repositorio). Secciones: ¿está vivo y a salvo?, dinero (patrimonio, drawdown, caja, capital invertido,
comisiones), órdenes y ejecuciones, estado de los datos y jobs, y recursos (heap, conexiones, edad de las métricas).

El JSON se generó con un script; para cambiarlo, edita el JSON o impórtalo a Grafana, ajústalo y exporta el resultado de
vuelta al repositorio.

## Alertas

`ops/prometheus/rules/trading.yml` (22 reglas; estándar `10-observability-app` de la fábrica). Gravedad con tres valores cerrados:
`page` (llega ya y se repite cada hora), `ticket` (agrupada, se repite cada 24 h) e `info` (nunca se envía). Todas llevan
`summary`, `description` y `runbook_url` (apartado «Qué hacer ante cada alerta» del [runbook](runbook.md)). Cuando algo se
resuelve llega un mensaje `RESOLVED`. Si el bot cae, se silencian las alertas derivadas (inhibición)
para no ahogarte en ruido.

Cubren lo que el bot **no puede ver de sí mismo** (caída, base de datos inalcanzable, copias que fallan en silencio) y, como
segundo canal, lo crítico del watchdog interno (kill switch, día atascado o fallido, orden de resultado desconocido, datos
obsoletos). Más las específicas del dinero: drawdown 10 % y 15 %, caída de más de 5 % en un día, caja negativa, órdenes
rechazadas.

**Telegram:** `TELEGRAM_BOT_TOKEN` y `TELEGRAM_CHAT_ID` de `.env`, los mismos que usa el bot. Sin ellos Alertmanager avisa en
su log al arrancar de que las alertas no te llegarán.

**Latido externo (opcional):** la regla `Watchdog` siempre está disparada. Si pones `HEARTBEAT_URL` (un check de
healthchecks.io u otro «dead-man's switch»), Alertmanager le envía un ping cada 5 minutos y el servicio te avisa cuando los
pings **dejan** de llegar: la única forma de enterarte de que cayó la máquina entera. Lo dijiste para «cuando el bot esté
probado»; ya está montado, solo falta la URL.

## Trazas (OpenTelemetry)

Micrometer Tracing con puente a OpenTelemetry exporta por OTLP al colector (`TRACING_ENABLED=true` en Compose; fuera de
Compose está apagado). Cada job programado (ingesta, arena, watchdog, lectura de métricas) genera una traza con su duración.
En Grafana: Explore → Tempo.

**Lo que NO está instrumentado:** las llamadas HTTP salientes (eToro, Yahoo) no tienen span propio; el cliente HTTP del JDK
no se instrumenta solo. Cuando exista el broker de eToro conviene envolver esas llamadas en una observación (latencia y errores
por endpoint). No lo hice antes de que exista el broker real para no instrumentar a ciegas.

## Comprobaciones automáticas

- `make check-ops` (y el job `ops-config` de CI): valida el Compose, las reglas de Prometheus (`promtool`) y la configuración
  de Alertmanager renderizada (`amtool`), con y sin Telegram y latido.
- `make smoke` (y el job `stack-smoke` de CI, ~3 min): levanta **todo** en un proyecto desechable y comprueba la cadena entera:
  bot sano → Prometheus lo recoge → hay métricas de negocio → Alertmanager recibe el latido → Grafana tiene el dashboard →
  Tempo recibe trazas → PostgreSQL no está publicado.
- Tests de integración (`BusinessMetricsTest`) con PostgreSQL real: valores por agente, drawdown, retirada de agentes, base
  caída (se conservan los últimos valores y se cuenta el fallo) y la medida de la copia.

## Desplegar en un servidor (Azure VM)

El bot corre en una sola máquina virtual con Docker Compose, igual que en tu equipo. `infra/terraform/vm.tf` la crea (apagada
por defecto: `enable_vm = true` para crearla; cuesta dinero mientras exista):

- Ubuntu 24.04, 4 GB de RAM (`Standard_B2s`), disco de 64 GB, **solo SSH y solo desde tu IP**, sin contraseña (clave SSH).
  Grafana no se expone nunca: túnel SSH.
- IP pública **estática**: es la dirección que vería eToro, por si hay que permitirla allí.
- Identidad gestionada de la máquina: lee el Key Vault y escribe las copias, sin ninguna clave guardada.
- Cuenta de almacenamiento para **copias fuera de la máquina**: sin claves de cuenta (solo Entra ID), TLS 1.2, red denegada
  salvo tu IP y la de la VM, versionado y papelera de 30 días, redundancia geográfica, borrado automático a 90 días. Una
  copia en el mismo disco que la base de datos no protege de perder el disco.
- `cloud-init`: Docker, el repositorio en `/opt/adri-trader`, fail2ban, actualizaciones de seguridad automáticas y un
  temporizador de systemd que sube las copias cada noche a las 04:00 UTC (`ops/offsite/ship-backups.sh`).

```bash
# una vez, desde tu equipo
cd infra/terraform && cp terraform.tfvars.example terraform.tfvars   # enable_vm = true, ssh_public_key, tu IP
export TF_VAR_db_admin_password=...  && terraform apply
ssh deploy@$(terraform output -raw vm_public_ip)

# en la VM
cd /opt/adri-trader && make init          # genera .env; añade TELEGRAM_* y, más adelante, las claves de eToro
scripts/deploy.sh 0.1.0                   # descarga, VERIFICA LA FIRMA y arranca por digest
ssh -L 3000:127.0.0.1:3000 deploy@<ip>    # desde tu equipo, para ver Grafana
```

`scripts/deploy.sh` **no arranca una imagen que no esté firmada por el workflow de release de este repositorio** (cosign
sin clave). Necesita que exista una versión publicada, es decir, la primera etiqueta `v0.x.y`.

Como el bot guarda su base de datos en la propia VM (PostgreSQL en Compose), el servidor gestionado de `main.tf` queda como
alternativa para practicar; no se usa en este despliegue.

## Qué no está verificado

- **La entrega a Telegram desde Alertmanager.** La configuración valida y el flujo hasta Alertmanager está probado, pero no he
  enviado un mensaje real (necesita tu bot). Prueba: `make up`, y para forzar una alerta `docker compose stop app`
  y espera 5 minutos: debe llegar `BotDown`.
- Los umbrales de alerta son razonables, no calibrados con datos reales de operación.
- El dashboard se verificó en sus 47 consultas (todas evalúan sin error), no mirándolo con datos de varias semanas.
- Retención de métricas, trazas y copias en disco: dimensionada a ojo para un volumen pequeño.
- **Todo el apartado de la VM**: `terraform validate` y Trivy pasan y la plantilla de cloud-init se renderiza, pero **nunca se ha
  aplicado** ni se ha arrancado una VM. `ship-backups.sh` (contenedor de Azure CLI con identidad gestionada) y `deploy.sh`
  (verificación cosign) tampoco se han ejecutado contra Azure ni contra una imagen publicada. Esperar algún ajuste al primer uso.
- Las imágenes fijadas dentro de `scripts/` (Azure CLI, cosign) no las actualiza Dependabot: revisarlas a mano de vez en cuando.
- Sin la subida a Azure, las copias viven en un volumen de la misma máquina y no protegen de perder el disco.

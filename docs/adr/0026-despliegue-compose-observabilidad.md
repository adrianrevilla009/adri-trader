# 0026. Despliegue con Docker Compose, observabilidad y VM en Azure

- **Estado:** Aceptada
- **Fecha:** 2026-11-20
- **Fuente:** decisión del titular; [ADR-0009](0009-despliegue.md); [docs/observability.md](../observability.md); brief, «Encaje con ai-project-factory»

## Contexto

El `docker-compose.yml` solo tenía PostgreSQL. Se planteó Kubernetes ligero con Argo CD. El bot es **un solo proceso** (la
aplicación Spring Boot) más su base de datos y debe haber **exactamente una instancia** (reclamo atómico del día, escritor
único): un orquestador no aporta nada. Además se quieren paneles y alertas del flujo de dinero.

## Decisión

- **Docker Compose en una sola máquina** (confirma ADR-0009). No Kubernetes ni Argo CD: excepción de la fábrica ya registrada
  en el brief (ADR-0005 de la fábrica).
- **Observabilidad en un perfil de Compose:** Prometheus (métricas y 22 reglas de alerta), Alertmanager (Telegram y latido
  externo opcional), Grafana (panel aprovisionado desde el repositorio), colector OpenTelemetry y Tempo (trazas de los jobs).
  Las métricas de negocio se leen de la base de datos por un puerto de lectura y se publican con Micrometer.
- **Copias de seguridad verificadas** cada noche, su antigüedad como métrica con alerta, y copia fuera de la máquina.
- **VM en Azure con Terraform** (apagada por defecto): excepción a «no se usa Terraform ni cloud» del brief, pedida por el
  titular. SSH solo desde su IP; sin claves de cuenta de almacenamiento; despliegue por digest tras verificar la firma cosign.
- Los puertos de Prometheus, Alertmanager, Tempo y PostgreSQL no se publican; solo Grafana, en 127.0.0.1.

## Consecuencias

- Todo arranca con `make up`; `make smoke` y el job `stack-smoke` de CI comprueban la cadena completa.
- Coste de la VM mientras exista; no hay alta disponibilidad (una máquina, una instancia, por diseño).
- **No verificado:** entrega real a Telegram, la VM de Azure (nunca aplicada), umbrales de alerta con datos reales, y las
  llamadas HTTP salientes (eToro, Yahoo) sin traza propia hasta que exista el broker real. Ver el documento de observabilidad.

# Registro de decisiones (ADR)

Cada fila de «Decisiones cerradas» del [brief](../brief.md) tiene su ADR (0001–0020); las 0021–0024 recogen decisiones de arquitectura del brief; las 0025–0027 son posteriores (arquitectura hexagonal, despliegue y observabilidad, pipeline de CI/CD). Un cambio exige un ADR nuevo: las aceptadas no se reescriben, se marcan como sustituidas.

| ADR | Título |
| --- | --- |
| [0001](0001-universo-v1.md) | Universo v1: ETFs UCITS de acumulación en euros (Xetra) |
| [0002](0002-estrategias-v1.md) | Estrategias v1 |
| [0003](0003-ritmo-decision.md) | Ritmo: una decisión diaria tras el cierre de Xetra |
| [0004](0004-ejecucion.md) | Ejecución: apertura de t+1 con slippage; real con órdenes limitadas |
| [0005](0005-datos-historicos.md) | Datos históricos: fuente gratuita por defecto, ADR si hay que pagar |
| [0006](0006-broker.md) | Broker: eToro (API pública beta), primero en demo y sin depósito |
| [0007](0007-capital.md) | Capital: 100 € simulados por agente y 100 € reales con un agente |
| [0008](0008-escalado.md) | Escalado del capital por evidencia |
| [0009](0009-despliegue.md) | Despliegue: Docker Compose en un servidor Linux pequeño |
| [0010](0010-sesion-broker.md) | Sesión del broker: clave de API por entorno y parada segura |
| [0011](0011-alertas-informes.md) | Alertas e informes: Telegram e informe HTML nocturno |
| [0012](0012-stack.md) | Stack: Java 21, Maven multimódulo, Spring Boot, PostgreSQL, ta4j, sin Lombok |
| [0013](0013-tiempo.md) | Tiempo: Clock inyectable, UTC y calendario por bolsa |
| [0014](0014-umbrales-fase2.md) | Umbrales de aceptación de la fase 2 |
| [0015](0015-umbrales-fase4.md) | Umbrales de la fase 4: vivo frente a backtest |
| [0016](0016-limites-riesgo.md) | Límites de riesgo iniciales |
| [0017](0017-impuestos.md) | Impuestos: TaxLedger revisado por asesor y aviso del modelo 720 |
| [0018](0018-fuera-de-v1.md) | Fuera de la v1 |
| [0019](0019-repositorio.md) | Repositorio, convenciones y CI |
| [0020](0020-sin-adaptador-python.md) | Sin adaptador en Python |
| [0021](0021-interfaz-estrategia-unica.md) | Una sola interfaz de estrategia para backtest, arena y real |
| [0022](0022-simulador-propio-arena.md) | La arena usa un simulador propio, no solo el demo del broker |
| [0023](0023-solo-postgresql.md) | Persistencia: solo PostgreSQL |
| [0024](0024-ta4j-motor-propio.md) | ta4j para señales y motor propio para la cartera |
| [0025](0025-arquitectura-hexagonal.md) | Arquitectura hexagonal en multimódulo, DDD táctico y CQRS de nivel 1 |
| [0026](0026-despliegue-compose-observabilidad.md) | Despliegue con Docker Compose, observabilidad y VM en Azure |
| [0027](0027-pipeline-ci-cd.md) | Pipeline de CI/CD y seguridad de la cadena de suministro |

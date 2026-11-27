# 0030. Agentes dinámicos: configuración en base de datos, API y UI

- **Estado:** Aceptada
- **Fecha:** 2026-11-27
- **Fuente:** decisión del titular; complementa [ADR-0028](0028-ciclo-diario-demo-etoro.md) y [ADR-0008](0008-escalado.md);
  cambia la excepción «sin frontend» del [PRD](../PRD.md) §11

## Contexto

El ciclo diario contra eToro usa un solo agente (`buy-and-hold` sobre un ISIN) fijado en `application.yml`. La arena simulada ya
tiene varios agentes, pero también fijos en código. El titular quiere crear, parar y reanudar agentes con estrategias distintas
sin tocar ficheros ni reiniciar, y poder compararlos en la cuenta demo.

## Decisión

- **Agentes en base de datos.** Un agente es una fila: estrategia, parámetros, capital, pasos de escalado y estado
  (`ACTIVE`, `STOPPED`). El ciclo diario lee los agentes activos de ahí. `trade_order`, `cash_ledger` y `trading_day` ya llevan
  `agent_id`.
- **Catálogo de estrategias abierto.** Cada estrategia se registra en el backend con identificador, descripción y esquema de
  parámetros (tipo, valor por defecto, mínimo, máximo). `GET /strategies` lo publica y la UI construye el formulario a partir de
  él. Una estrategia nueva es una clase nueva en el backend, no cambia la API ni la UI. **No se sube código desde la UI.**
- **Capital y pasos de escalado por agente**, editables y guardados en base de datos. Cada cambio de configuración queda en una
  tabla de auditoría (cuándo, quién, valor anterior y nuevo). La condición «sin incidentes» para subir un paso es una
  confirmación manual.
- **Reglas que el servidor impone siempre:** capital por agente compatible con el mínimo por orden y el 25 % por activo
  (ADR-0016); el tope por orden de la demo (`max-order-amount`) no se puede saltar desde la API.
- **Parar un agente** = no envía órdenes nuevas y conserva sus posiciones. Cerrarlas es una acción explícita aparte.
- **Un agente nuevo opera desde el siguiente ciclo diario.**
- **Modo DEMO/PROD.** Existe como campo del sistema y de la API; hoy solo se acepta `DEMO` y cualquier otro valor da un error
  claro. El cambio real a PROD (con MFA) queda para la fase 2 y requerirá su propia ADR.
- **API.** Vive en `trading-adapter-web`, con casos de uso en `trading-application`. Token en cabecera (como el kill switch),
  escucha solo en loopback por defecto. Cada cambio queda auditado.
- **UI.** React con Vite y TypeScript en `frontend/`, fuera de Maven, con su propio job en el CI (el check `CI gate` lo incluye).

## Consecuencias

- Se puede probar varias estrategias a la vez en la demo y compararlas con sus gemelos simulados.
- Hay un frontend y Node en el proyecto: una excepción nueva a la fábrica, registrada aquí.
- La superficie de ataque crece: la API puede crear agentes que envían órdenes. Por eso va con token, auditoría y solo DEMO.
- **No verificado:** que eToro acepte varios agentes operando el mismo ETF el mismo día sin chocar con sus reglas de ETF
  parecidos; el límite de 20 escrituras por minuto con varios agentes.

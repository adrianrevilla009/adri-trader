# Estrategia de pruebas

Decisión: [ADR-0032](adr/0032-estrategia-de-pruebas.md). Este bot maneja dinero (hoy, dinero de demo), así que **un test que no puede
fallar no vale**. Por eso hay muchas capas y, encima, una prueba de las propias pruebas (mutación).

## Las capas y dónde viven

| Capa | Qué comprueba | Dónde | Cómo se ejecuta |
| --- | --- | --- | --- |
| **Unitaria** | una clase o regla, con valores en los bordes | `src/test` de cada módulo (`AgentConfigTest`, `RulesBoundaryTest`, `TotpTest`…) | `make test` |
| **Propiedades** | reglas que valen para *miles* de entradas aleatorias (semilla fija, reproducible) | `trading-core/.../PropertyTest` | `make test` |
| **Componente** (backend) | un caso de uso con sus puertos reales y dobles de prueba | `trading-application/src/test` (`ModeServiceTest`, `AgentServiceTest`…) | `make test` |
| **Componente** (pantalla) | cada componente React aislado, con Testing Library | `frontend/src/components/*.test.tsx`, `App.test.tsx` | `make ui-test` |
| **Integración** | adaptador + base de datos real (Testcontainers) | `trading-adapter-persistence`, `trading-app` (`MigrationsTest`, `ApiContextTest`…) | `make test` (Docker) |
| **Contrato** | servidor y pantalla cumplen **el mismo fichero** `contract/api-contract.json` | `ApiContractTest` (servidor) y `frontend/src/contract.test.ts` (cliente) | `make test` y `make ui-test` |
| **Seguridad / fuzz** | entradas hostiles contra la API real: nunca un 5xx, nunca algo creado | `ApiSecurityTest` (75 casos) | `make test` |
| **Carga y carreras** | muchos clientes a la vez: nada se pierde ni se duplica, la latencia aguanta | `ApiLoadTest`, `ModeServiceChaosTest` | `make test` |
| **Caos** | se rompe una pieza a mitad de uso y el sistema falla cerrado y se recupera | `DatabaseChaosTest` (base de datos congelada), `ModeServiceChaosTest` (alertas y registro caídos, reloj que retrocede), `ExecutionFailureTest` (bróker que falla) | `make test` |
| **Extremo a extremo** | el bot real (jar) + PostgreSQL real + el **cliente real** de la pantalla + códigos MFA reales, con reinicio a mitad | `scripts/e2e.sh`, `frontend/e2e/` | `make e2e` (Docker) |
| **Arquitectura** | las reglas hexagonales no se rompen | ArchUnit en `trading-app/.../architecture` | `make test` |
| **Mutación** | cambia el código (invierte una comparación, quita una llamada…) y exige que algún test falle | `scripts/mutation.sh` (PIT) | `make mutation` |
| **Humo de la pila** | compose entero levanta y se observa | `scripts/smoke-stack.sh` | `make smoke` |

## Mutación: la prueba de las pruebas

La cobertura dice qué líneas *se ejecutan*; la mutación dice si los tests *notan* un cambio. Puntuación mínima por módulo (la
puerta falla por debajo):

| Módulo | Mínimo | Medido |
| --- | ---: | ---: |
| `trading-core` (dominio) | 90 % | 96 % |
| `trading-application` (`ModeService`, `ProdSwitchPolicy`, `AgentService`, `AgentsDayService`) | 95 % | 100 % |
| `trading-risk` (reglas de riesgo) | 95 % | 96 % |
| `trading-execution` (envío, conciliación, estados) | 95 % | 99 % |

Un mutante que sobrevive es un test que falta. Informe: `<módulo>/target/pit-reports/index.html`.

**Fuera de la puerta (declarado, no oculto):** `trading-strategies` (68 %), adaptadores, `trading-app` y el frontend no tienen
puntuación mínima de mutación. Las estrategias son lógica de investigación y se afinarán con las estrategias mismas.

## Qué encontraron estas pruebas (errores reales, ya corregidos)

- Una petición sin `strategyId` o con un paso `null` daba **500** en lugar de 400 (`NullPointerException`).
- Un capital como `1e999999999` podía **agotar la memoria** al normalizarlo: ahora se rechaza por magnitud.
- Con la base de datos congelada, **una sola petición colgada bloqueaba toda la API** (un solo hilo): ahora hay 16 hilos y
  tiempos de espera de conexión (5 s) y de respuesta (30 s).
- `TRUNCATE` **saltaba los disparadores** que hacen inmutables el libro de dinero y las auditorías: la migración V10 lo cierra.
- Un fallo al enviar una alerta podía alterar el resultado de un cambio de modo: ahora la alerta es de mejor esfuerzo y se registra.
- Dos peticiones simultáneas del mismo agente podían entrelazar lectura y escritura: los comandos de `AgentService` van sincronizados.

## Cómo añadir una prueba bien

1. Primero el borde: vacío, uno, justo en el límite, justo por encima, `null`, texto hostil.
2. Que pueda fallar: comprueba el valor, no solo «no lanza».
3. Si cambias una regla de dinero o acceso, corre `make mutation`: no debe bajar la puntuación.
4. Un contrato nuevo (ruta o campo) se añade primero a `contract/api-contract.json`; los dos lados fallarán hasta cumplirlo.

## Lo que NO está cubierto

- **Navegador real** (Playwright o similar): la pantalla se prueba con jsdom y con el cliente real contra el bot, pero no en un
  navegador de verdad. Añadirlo sería una dependencia nueva.
- **La API real de eToro con varios agentes** y **la entrega de alertas a Telegram**: no hay forma de probarlas sin tocar la cuenta
  o el canal real.
- **Carga a escala real** (miles de agentes, horas): las pruebas de carga son cortas a propósito para correr en cada cambio.
- **Pruebas de penetración**: `ApiSecurityTest` cubre lo que se puede automatizar, no sustituye a una revisión humana.

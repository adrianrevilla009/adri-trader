# 0032. Estrategia de pruebas por capas y mutación como puerta

- **Estado:** Aceptada
- **Fecha:** 2026-11-28
- **Fuente:** decisión del titular («pruebas exhaustivas… totalmente blindado»); detalle en [docs/testing.md](../testing.md)

## Contexto

El bot maneja dinero y expone una API con un cambio a PROD. La cobertura de líneas decía que casi todo se ejecutaba, pero no si los
tests *notarían* un error. Al probar con entradas hostiles, carga, caos y mutación aparecieron fallos reales que ninguna prueba previa
veía (500 por `null`, agotamiento de memoria con un número enorme, una API que se bloqueaba con la base de datos congelada, `TRUNCATE`
que saltaba la inmutabilidad del libro).

## Decisión

1. Pruebas por capas: unitaria, de propiedades, de componente, de integración (base de datos real), de **contrato** (un único fichero
   `contract/api-contract.json` que cumplen servidor y cliente), de seguridad/fuzz, de carga y carreras, de **caos**, extremo a extremo
   (bot real + base de datos real + cliente real + MFA real + reinicio) y de arquitectura.
2. **Mutación (PIT) como puerta de CI** sobre el código que protege dinero y acceso, con puntuación mínima por módulo
   (`scripts/mutation.sh`): dominio 90 %, servicios de aplicación 95 %, riesgo 95 %, ejecución 95 %.
3. El job `e2e` y el job `mutation` entran en `CI gate`.
4. Los tests de los casos de uso viven en `trading-application` (junto al código); `trading-app` los reutiliza por `test-jar`.
5. Sin dependencias nuevas de prueba en el código de producción: PIT vive en un perfil Maven (`-Pmutation`); las propiedades se
   escriben a mano con semilla fija.

## Consecuencias

- El CI es más lento (≈ 3 min más de mutación y ≈ 2 min de e2e, en paralelo con el resto).
- Un cambio que baje la puntuación de mutación de esos módulos no se puede fusionar: hay que añadir el test que falta.
- Quedan fuera de la puerta, declarado: estrategias, adaptadores, `trading-app` y frontend (sin puntuación de mutación), el navegador
  real, la API real de eToro con varios agentes y la entrega a Telegram.

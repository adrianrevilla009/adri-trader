# 0031. Cambio a PROD con MFA y varios guardas

- **Estado:** Aceptada
- **Fecha:** 2026-11-28
- **Fuente:** decisión del titular; desarrolla el campo DEMO/PROD de la [ADR-0030](0030-agentes-dinamicos-api-ui.md)

## Contexto

La ADR-0030 dejó el modo DEMO/PROD como campo, con PROD cerrado hasta la fase 2 «con MFA». El titular pide dejar montado el
cambio, con una función apagada por defecto y muchos guardas defensivos, porque es una operación delicada.

**Lo que esta ADR NO hace:** no existe un bróker real. `trading.etoro.mode` solo acepta DEMO y la puerta de dinero real
(`RealTradingGate`) sigue cerrada. Cambiar a PROD **no abre ninguna vía de operar con dinero real**; solo mueve el
interruptor y deja de operar el ciclo diario (ver abajo). Un bróker real exigirá otra ADR.

## Decisión

Para pasar de DEMO a PROD se cumplen **todos** estos guardas, en este orden:

1. **Función apagada por defecto** (`trading.prod.enabled=false`). Apagada, no hay ruta posible: se rechaza, se registra y se avisa.
   Encendida, exige un secreto MFA (`TRADING_PROD_MFA_SECRET`, Base32, al menos 80 bits) o la aplicación no arranca.
2. **Token de la API** (ADR-0030) y que la API esté encendida.
3. **Sin atajo:** `PUT /api/system/mode` con PROD siempre se rechaza (409); solo vale el proceso de dos pasos.
4. **Paso uno, desafío** (`POST /api/system/prod/challenge`): un identificador aleatorio de 128 bits, de **un solo uso**, con
   caducidad (120 s por defecto), ligado al actor y **uno pendiente a la vez** (uno nuevo anula el anterior).
5. **Paso dos, confirmación** (`POST /api/system/prod/confirm`): el desafío, la **frase exacta** «SWITCH TO PROD» y un **código MFA
   TOTP** (RFC 6238, 6 cifras, ±1 paso) que no se haya usado antes (**anti-repetición**).
6. **Cualquier fallo gasta el desafío**, cuenta hacia el **bloqueo** (3 fallos → 15 minutos, 423) y la respuesta es siempre la misma
   (403 «rechazado») para no revelar qué factor falló; el motivo real va al registro.
7. **Registro antes del cambio:** cada intento (desafío, rechazo, bloqueo, cambio) se escribe en `mode_audit`, que no admite `UPDATE`
   ni `DELETE` y nunca guarda códigos, secretos ni tokens. **Si el registro falla, el cambio no ocurre.**
8. **Avisos:** cada paso avisa por Telegram (WARNING; el cambio y el bloqueo, CRITICAL).
9. **Pantalla:** tres pantallas (avisos con tres casillas, código + frase escrita, última confirmación), cuenta atrás visible, y un
   banner rojo permanente mientras el modo sea PROD.

**Volver a DEMO** es la dirección segura: solo token, una confirmación en pantalla, registro y aviso; sin MFA.

**El modo vive en memoria y arranca en DEMO en cada arranque**: reiniciar siempre devuelve al estado seguro, y PROD hay que volver
a armarlo con MFA.

**Efecto de estar en PROD hoy:** `AgentsDayService` **no ejecuta ningún agente** y avisa (CRITICAL) en cada ciclo, porque no hay
bróker real. Es la opción que falla cerrada: PROD no puede acabar operando en la cuenta demo creyendo que es real.

## Consecuencias

- El cambio funciona de punta a punta y está probado, pero es un interruptor sin bróker detrás: sigue siendo trabajo futuro el bróker
  real, su gate, límites de escalado (ADR-0008) y quizá modo PROD por agente (hoy `AgentConfig` solo acepta DEMO).
- El MFA es el segundo factor frente al token: quien robe solo el token no puede pasar a PROD.
- El actor (`X-Actor`) lo manda el cliente y solo sirve para el registro; la seguridad la dan el token y el MFA, no el actor.
- Repetición de un código tras un reinicio dentro de 90 s: el registro de «código usado» está en memoria; el riesgo es mínimo porque
  el desafío también está en memoria y el reinicio los anula a ambos.
- El secreto MFA vive en el entorno (`.env`, ignorado por git). Quien lo tenga puede generar códigos: protégelo como el token.

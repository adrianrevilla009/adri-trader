# Pantalla de agentes (React)

Crea, cambia, para y reanuda agentes de trading, y muestra el historial de cambios. Decisión: [ADR-0030](../docs/adr/0030-agentes-dinamicos-api-ui.md).
Habla con la API local del bot (`AgentsApiServer`); no tiene lógica de negocio: el servidor valida todo y la pantalla solo lo enseña.

## Usarla

```
# 1. En .env del bot: TRADING_API_TOKEN=<16 o más caracteres>  y arrancar el bot (make up, o mvn spring-boot:run en trading-app)
# 2. Desde la raíz del repositorio:
make ui-install     # una vez (npm ci, sin scripts de instalación)
make ui             # http://127.0.0.1:5173
```

Pide el token y lo guarda solo en esa pestaña (`sessionStorage`). En desarrollo, Vite reenvía `/api` a `http://127.0.0.1:8100`
(cambia el destino con `VITE_BACKEND_URL`), así que el navegador no necesita CORS.

## Qué hay

| Archivo | Para qué |
| --- | --- |
| `src/api.ts` | Cliente de la API: manda el token, convierte errores en `ApiError` con el mensaje del servidor |
| `src/App.tsx` | Pantalla: token, modo, lista de agentes, formularios, historial |
| `src/components/AgentForm.tsx` | Crear/editar. Los campos de parámetros salen del catálogo de estrategias: una estrategia nueva en el backend aparece sola |
| `src/components/AgentTable.tsx` | Lista con parar, reanudar, editar e historial |

## Comprobar

```
make ui-test        # tipos, tests (vitest) y build; es lo mismo que hace el CI
```

## Seguridad

- El token vive solo en la pestaña y viaja en la cabecera `Authorization`; nunca se guarda en disco ni en la URL.
- La API solo escucha en el equipo local. El modo PROD aparece desactivado y el servidor lo rechaza (MFA en la fase 2).
- Dependencias fijadas con `package-lock.json`, instalación con `npm ci --ignore-scripts` y `npm audit` en el CI.

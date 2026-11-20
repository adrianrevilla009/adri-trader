# Políticas ejecutables

Reglas de [ai-project-factory](https://github.com/adrianrevilla009/ai-project-factory) (`policies/`) adoptadas por este proyecto.
Las comprueba el CI en cada PR; aquí se explica qué hay y en qué se adaptaron.

| Política | Dónde | Qué impone | Cómo se ejecuta |
| --- | --- | --- | --- |
| ArchUnit | `trading-app/src/test/.../architecture` | Hexagonal: dominio y aplicación sin infraestructura, capas hacia dentro, adaptadores independientes, sin ciclos ([ADR-0025](../docs/adr/0025-arquitectura-hexagonal.md)) | `mvn verify` |
| Maven Enforcer | `pom.xml` y los poms de los módulos puros | Librerías prohibidas; **Spring, Jackson, JDBC y Micrometer prohibidos en los módulos de dominio y aplicación**, también de forma transitiva | `mvn validate` |
| Semgrep | `policies/semgrep/` (19 reglas) | Dinero en coma flotante, `@Scheduled` sin bloqueo, inyección por campo, SQL concatenado, migraciones inseguras, cripto débil, XXE, Lombok… | `make semgrep` o `scripts/semgrep.sh`; las pruebas de las reglas, `scripts/semgrep.sh --test` |

## Adaptaciones a este proyecto (y por qué)

- **`java-log-string-concat`**: la regla original marcaba cualquier método cuyo primer argumento fuera una concatenación (un alertador, una
  colección, el kill switch). Ahora solo marca una variable `log`/`logger` llamada con un nivel de log. Prueba en `java-logging.java`.
- **`sql-unsafe-migrations`**: excluye `V1`–`V6`, que ya están aplicadas: Flyway se niega a arrancar si cambia un solo byte de una
  migración aplicada (suma de comprobación). La regla sigue valiendo para cualquier migración nueva (`V7` y posteriores).
- **Maven Enforcer**: la versión mínima de Maven es 3.8.6 y no 3.9 como en el ejemplo de la fábrica, porque la máquina local tiene
  3.8.6 (CI y Docker usan 3.9).

## Excepciones puntuales

Se escriben en el propio código con `// nosemgrep: <regla> -- <motivo>`: generadores pseudoaleatorios con semilla (simulaciones
reproducibles, no criptografía), una probabilidad que la regla de dinero confunde con un importe, los `@Scheduled` (una sola instancia
por diseño, ADR-0009 y ADR-0026) y la salida por consola del CLI de backtest. Una excepción sin motivo no se acepta en revisión.

## Qué no está adoptado

Las reglas de Kafka, DynamoDB, JPA, LLM, frontend y datos de la fábrica no aplican a este proyecto. Kyverno, OPA/Conftest y las
comprobaciones de IaC de AWS no aplican (no hay Kubernetes ni AWS).

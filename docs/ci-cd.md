# CI/CD, seguridad de la cadena de suministro y entornos

Objetivo: que nada llegue a `main`, a una imagen publicada ni a un entorno sin pasar por comprobaciones automáticas,
porque este proyecto acabará manejando dinero. **El pipeline de CI está ejecutado y en verde. El flujo de release y de
promoción está escrito y validado en sintaxis, pero aún no se ha ejecutado de verdad** (necesita la primera etiqueta).

## Herramientas, por qué y qué no se usó

| Necesidad | Herramienta | Notas |
| --- | --- | --- |
| Tests | Maven + JUnit + Testcontainers (PostgreSQL real) | Resumen de cobertura (JaCoCo) en cada ejecución |
| Convención de commits | Conventional Commits: título del PR (CI) y hook `commit-msg` | Los squash merge usan el título del PR como mensaje |
| Secretos | gitleaks (historial completo) + Trivy (secretos) + secret scanning de GitHub con push protection + hook local | El allowlist de gitleaks es por valor exacto, no por carpeta |
| Vulnerabilidades de dependencias | Trivy sobre la **imagen** (lo que de verdad se ejecuta) + dependency-review en PR + Dependabot | Falla ante HIGH o CRITICAL con corrección disponible |
| Reglas de build | Maven Enforcer: librerías prohibidas (Lombok, log4j 1, commons-collections 3, joda-time), versiones de plugin fijadas, y **Spring, Jackson, JDBC y Micrometer prohibidos en los módulos de dominio y aplicación** (también de forma transitiva) | Política de la fábrica `maven-enforcer` |
| Arquitectura | ArchUnit dentro de `mvn verify` ([ADR-0025](adr/0025-arquitectura-hexagonal.md)) con pruebas negativas | Una regla no se relaja: se corrige el código o se escribe un ADR |
| Análisis estático (SAST) | CodeQL (Java y los propios workflows), consultas `security-extended`, semanal | Gratis en repos públicos |
| Calidad de código | SonarQube Cloud, **opcional** | Ver más abajo: requiere tu cuenta y un token |
| Infraestructura como código | `terraform fmt` + `validate` + tflint + Trivy (misconfig) | Sin credenciales de nube, nunca aplica nada |
| Contenedor | Dockerfile multi-etapa, base fijada por digest, usuario sin privilegios, prueba de arranque real contra PostgreSQL | |
| SBOM | CycloneDX (grafo Maven) y SPDX (imagen, con syft) como artefactos; SPDX adjunto firmado en el release | |
| Firma y procedencia | cosign sin clave (OIDC de GitHub) + atestaciones de procedencia y de SBOM | Se verifica al publicar y otra vez al promover |
| Registro de imágenes | **GitHub Container Registry (ghcr.io)** | No necesita ningún token adicional: usa el `GITHUB_TOKEN` del workflow |
| Cadena de acciones | Todas las acciones fijadas por SHA, actualizadas por Dependabot | Una etiqueta móvil comprometida no puede inyectar código |

**Docker Hub:** no hace falta. GHCR evita guardar un token externo. Si algún día se quisiera Docker Hub habría que crear un
secret `DOCKERHUB_TOKEN` con un token de solo escritura de ese repositorio, nunca la contraseña de la cuenta.

**No se usó, a propósito:** OWASP Dependency-Check (lento y pide una clave de NVD; Trivy cubre lo mismo con menos fricción),
Docker Scout o Snyk (cuentas de terceros), y firma con claves propias (la firma sin clave evita custodiar una clave privada).

## El pipeline (`.github/workflows/ci.yml`)

Se ejecuta en cada PR y en cada push a `main`. El job final **`CI gate`** es el único que exige la protección de rama:
falla si cualquiera de los anteriores falló.

| Job | Qué comprueba |
| --- | --- |
| PR title | El título cumple Conventional Commits |
| Build and test | `mvn verify`, resumen de cobertura, informes y SBOM de dependencias como artefactos |
| Terraform | fmt, validate (sin backend), tflint |
| Static policies | Semgrep con las reglas de la fábrica (`policies/semgrep`): dinero en coma flotante, `@Scheduled` sin bloqueo, inyección por campo, SQL concatenado, migraciones inseguras, cripto débil, XXE… Cualquier hallazgo falla |
| Operations config | `scripts/check-ops.sh`: Compose, reglas de Prometheus (`promtool`) y Alertmanager (`amtool`) |
| Whole stack | `scripts/smoke-stack.sh`: levanta todo el Compose y comprueba bot, Prometheus, alertas, Grafana y trazas |
| Secret scan | gitleaks sobre todo el historial |
| Config and secret scan | Trivy: Dockerfile, Terraform y secretos; cualquier hallazgo falla |
| Dependency review | Dependencias nuevas vulnerables en el PR |
| Image | Construye la imagen, escaneo de vulnerabilidades, SBOM SPDX, arranque real contra PostgreSQL y comprobación de usuario no root |
| Code quality | SonarQube Cloud (solo si está activado) |

Aparte: `codeql.yml` (SAST) y `dependabot.yml` (PRs semanales de Maven, acciones, Docker y Terraform).

**Resultados del primer escaneo (y arreglos):** 14 vulnerabilidades HIGH reales en las dependencias de la imagen (Jackson,
Micrometer, el driver de PostgreSQL, Spring Boot y spring-expression), corregidas subiendo Spring Boot a 3.5.14 y fijando
versiones en el `pom.xml` (marcadas como «override»: retirarlas cuando Spring Boot las incorpore); 8 hallazgos de Terraform
(ACL del Key Vault, TLS mínimo, registros de la base, caducidad del secreto…), con dos excepciones razonadas en el propio
código (`#trivy:ignore`); 8 falsos positivos de gitleaks (valores de ejemplo exactos).

## Release y promoción

1. **Release** (`release.yml`): al crear una etiqueta `vX.Y.Z` corre todo el CI sobre ese commit; si pasa, construye la
   imagen, la sube a `ghcr.io/adrianrevilla009/adri-trader`, la escanea **por digest** y solo si está limpia la firma,
   adjunta la procedencia y el SBOM y verifica la firma.
2. **Promoción** (`promote.yml`, manual): elige versión y entorno. Resuelve el digest (se promueve por digest, nunca por una
   etiqueta que se pueda mover), **verifica firma y atestaciones** y registra la aprobación. Una imagen sin firma válida no
   se promueve. Como el bot corre en tu máquina, no hay despliegue automático: el resumen indica la imagen exacta.

```bash
git tag v0.1.0 && git push origin v0.1.0        # lanza el release
# GitHub > Actions > Promote > Run workflow     # versión 0.1.0, entorno dev | paper | live
```

## Entornos

| Entorno | Para qué | Protección en GitHub |
| --- | --- | --- |
| `dev` | Desarrollo y pruebas | Ninguna |
| `paper` | Demo de eToro (órdenes simuladas por eToro) | El workflow de promoción solo puede ejecutarse desde `main` |
| `live` | Dinero real | **Aprobación manual obligatoria** (tuya) y solo desde `main` |

Que la imagen provenga de una etiqueta `v*` no lo da la rama sino la verificación de firma: el certificado de cosign
incluye el workflow y la etiqueta que la construyó, y la promoción exige que coincidan.

Los secretos de cada entorno (claves de eToro, Telegram, token del kill switch) se guardan como *environment secrets* de
GitHub, no en el repositorio ni en la imagen. Cada entorno usa su perfil de Spring (`dev`, `paper`, `live`).

## Protección de la rama `main`

PR obligatorio; el check `CI gate` y el análisis de CodeQL en verde; la rama al día con `main`; historial lineal;
conversaciones resueltas; sin force push ni borrado. No se exige aprobación de un segundo revisor porque el proyecto tiene
un único mantenedor; CODEOWNERS pide revisión a la persona propietaria en rutas sensibles (ejecución, riesgo, broker,
impuestos, migraciones, pipeline, infraestructura).

## Hooks locales

Hooks de git sin dependencias en `.githooks/` (no hace falta Python). Se activan con `make hooks`:

- `commit-msg`: Conventional Commits.
- `pre-commit`: no se commitea en `main`, nada de `.env`, `.tfstate`, claves, patrones de tokens, formato de Terraform y
  espacios en blanco.
- `pre-push`: no se empuja a `main` y el código debe compilar.

## SonarQube Cloud (opcional, y por qué)

Requiere cosas tuyas: crear el proyecto en SonarQube Cloud con la organización y la clave de `sonar-project.properties`,
guardar el secret `SONAR_TOKEN` y la variable del repositorio `SONAR_ENABLED=true`. Hasta entonces el job se omite.
Sobre las reglas: conviene usar el *quality gate* por defecto de Sonar, que mira solo el **código nuevo** («clean as you
code»), de modo que el código actual no bloquea el pipeline y solo se exige calidad a lo que se añade. Las reglas que
molesten se desactivan en Sonar con una razón escrita, no con cambios en el código.

## Qué sigue sin estar verificado

- El workflow de release y el de promoción: sintaxis validada con actionlint, pero **nunca ejecutados**. La primera etiqueta
  puede necesitar ajustes (permisos del registro, formato de atestaciones).
- Los entornos y la protección de rama se configuran por API de GitHub; revisa en *Settings* que quedaron como se espera. **No se ha podido probar un push directo a `main` para ver el rechazo** (la herramienta lo bloqueó); la protección está comprobada solo por lo que devuelve la API.
- SonarQube: sin ejecutar hasta que lo actives.

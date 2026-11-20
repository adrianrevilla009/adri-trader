# 0027. Pipeline de CI/CD y seguridad de la cadena de suministro

- **Estado:** Aceptada
- **Fecha:** 2026-11-20
- **Fuente:** [docs/ci-cd.md](../ci-cd.md); [ADR-0019](0019-repositorio.md)

## Contexto

El proyecto acabará manejando dinero: nada debe llegar a `main`, a una imagen publicada ni a un entorno sin comprobaciones
automáticas. El ADR-0019 fijó GitHub Actions; faltaba decidir las herramientas y las reglas.

## Decisión

- **Un único check obligatorio, `CI gate`**, que agrega: pruebas (Maven y Testcontainers), título de PR con Conventional
  Commits, secretos (gitleaks sobre todo el historial y Trivy), dependencias nuevas vulnerables (dependency-review), imagen
  (Trivy y arranque real contra PostgreSQL), Terraform (fmt, validate, tflint y Trivy), configuración de operaciones y stack
  completo. CodeQL (Java y workflows) como segundo check obligatorio. SonarQube Cloud opcional.
- **Acciones fijadas por SHA**, imágenes base y de Compose fijadas por digest, Dependabot para actualizarlas; las migraciones
  mayores (Spring Boot, JDK, azurerm) se ignoran a propósito y se deciden en un ADR.
- **Registro ghcr.io** con el token del propio workflow (sin token de Docker Hub). Release por etiqueta `vX.Y.Z`: CI completo,
  construcción, escaneo por digest, firma cosign sin clave y atestaciones de procedencia y SBOM. Promoción por digest verificando
  la firma. Entornos `dev`, `paper` y `live` (este último con aprobación manual y solo desde `main`).
- **Protección de `main`:** PR obligatorio, checks en verde, rama al día, historial lineal, sin force push.
- **Ganchos locales** sin dependencias (`make hooks`): commits convencionales, sin secretos, sin commits ni pushes a `main`.
- **Pruebas de arquitectura** ([ADR-0025](0025-arquitectura-hexagonal.md)) dentro de `mvn verify`.

## Consecuencias

- Una vulnerabilidad HIGH corregible, un secreto o una violación de arquitectura bloquean el merge.
- **No verificado:** el flujo de release y promoción no se ha ejecutado nunca (necesita la primera etiqueta); la protección
  de rama está comprobada solo por lo que devuelve la API.

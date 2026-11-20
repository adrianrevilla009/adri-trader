# Infraestructura en Azure (esqueleto para practicar)

Esqueleto de Terraform. **No se despliega desde CI y el bot funciona sin esto**: corre en tu máquina con
`docker compose`. Sirve para practicar Azure con una base de datos gestionada.

## Qué crea

| Recurso | Para qué |
| --- | --- |
| Resource group | Contenedor de todo; `terraform destroy` lo borra limpio |
| PostgreSQL Flexible Server 16 (B1ms) + base `trading` | Misma base que `docker-compose.yml`, gestionada, con copias a 7 días |
| Regla de firewall | Solo tu IP pública puede conectar. Nunca abrir 0.0.0.0/0 |
| Key Vault (RBAC) | Dónde guardar secretos (claves de eToro, Telegram, token del kill switch). Nunca en git |
| Log Analytics workspace | Destino futuro de logs y métricas (`/metrics` en formato Prometheus) |

## Uso

```bash
az login
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars     # rellena subscription_id y tu IP
export TF_VAR_db_admin_password='una-clave-de-16-o-mas-caracteres'
terraform init
terraform plan
terraform apply
terraform destroy                                  # cuando termines de practicar
```

Para apuntar la app a esa base: `DB_URL=jdbc:postgresql://<postgres_host>:5432/trading?sslmode=require`,
`DB_USER` y `DB_PASSWORD` por variables de entorno.

## Estado y límites

- Validado solo con `terraform validate` (sintaxis y tipos). **Nunca se ha aplicado**: no hay plan contra una
  suscripción real, así que nombres, tamaños y disponibilidad por región pueden requerir ajustes al ejecutarlo.
- Los nombres de Key Vault y PostgreSQL son globales en Azure: si `apply` dice que existen, cambia `project`.
- El estado de Terraform es local y está en `.gitignore` (contiene secretos). Para un estado compartido, un
  backend `azurerm` en Azure Storage.
- El Key Vault guarda por ahora solo la clave de la base. Las claves de eToro, Telegram y del kill switch se
  añaden a mano cuando existan.
- Aún no hay contenedor de la aplicación ni Container Apps: el siguiente paso natural si algún día se quiere
  desplegar en Azure.

## VM para Docker Compose (opcional)

`vm.tf` crea la máquina que ejecuta todo el stack, su red (SSH solo desde tu IP), una IP pública estática y una cuenta de
almacenamiento para las copias fuera de la máquina. Está apagada por defecto (`enable_vm = false`). Detalle y pasos en
[docs/observability.md](../../docs/observability.md#desplegar-en-un-servidor-azure-vm). **Nunca aplicada.**

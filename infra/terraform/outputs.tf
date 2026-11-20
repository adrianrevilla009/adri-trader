output "resource_group" {
  value = azurerm_resource_group.main.name
}

output "postgres_host" {
  description = "Use as DB_URL=jdbc:postgresql://<host>:5432/trading?sslmode=require"
  value       = azurerm_postgresql_flexible_server.main.fqdn
}

output "key_vault_uri" {
  value = azurerm_key_vault.main.vault_uri
}

output "log_analytics_workspace_id" {
  value = azurerm_log_analytics_workspace.main.workspace_id
}

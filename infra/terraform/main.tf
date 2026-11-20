terraform {
  required_version = ">= 1.5"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }

  # Learning setup: state stays on your machine (git-ignored). When you want a shared state, move it to an
  # Azure Storage backend with a `backend "azurerm" {}` block.
}

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}

data "azurerm_client_config" "current" {}

locals {
  prefix = "${var.project}-${var.environment}"
  tags = {
    project     = var.project
    environment = var.environment
    managed_by  = "terraform"
  }
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${local.prefix}"
  location = var.location
  tags     = local.tags
}

# --- Database: the same PostgreSQL 16 as docker-compose, as a managed server ---------------------------------

resource "azurerm_postgresql_flexible_server" "main" {
  name                = "psql-${local.prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  version             = "16"

  administrator_login    = var.db_admin_user
  administrator_password = var.db_admin_password

  sku_name   = var.db_sku_name # smallest burstable size: the cheapest way to practice
  storage_mb = var.db_storage_mb

  backup_retention_days         = 7
  public_network_access_enabled = true # reachable from your IP only, see the firewall rule below

  tags = local.tags
}

resource "azurerm_postgresql_flexible_server_database" "trading" {
  name      = "trading"
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# Only your own public IP may connect. Never open 0.0.0.0/0.
resource "azurerm_postgresql_flexible_server_firewall_rule" "my_ip" {
  name             = "allow-my-ip"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = var.my_public_ip
  end_ip_address   = var.my_public_ip
}

# --- Secrets: eToro keys, Telegram token and kill switch token live here, never in git -----------------------

resource "azurerm_key_vault" "main" {
  name                       = "kv-${local.prefix}"
  resource_group_name        = azurerm_resource_group.main.name
  location                   = azurerm_resource_group.main.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = false # learning setup: lets you destroy and recreate with the same name
  soft_delete_retention_days = 7
  tags                       = local.tags
}

# The person running terraform may manage secrets in the vault.
resource "azurerm_role_assignment" "vault_admin" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_key_vault_secret" "db_password" {
  name         = "db-admin-password"
  value        = var.db_admin_password
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_role_assignment.vault_admin]
}

# --- Monitoring: a Log Analytics workspace to send logs and metrics to later --------------------------------

resource "azurerm_log_analytics_workspace" "main" {
  name                = "log-${local.prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = local.tags
}

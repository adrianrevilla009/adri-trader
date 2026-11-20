# --- The machine that runs docker-compose (bot, PostgreSQL, monitoring) ----------------------------------------
# Everything here is off unless enable_vm = true, so the default footprint (and cost) does not change.
# NEVER APPLIED: validated with `terraform validate`, tflint and Trivy only. See README.md.

locals {
  vm_count = var.enable_vm ? 1 : 0
  # globally unique, lower case, 3-24 characters
  storage_name = "st${replace(local.prefix, "-", "")}${substr(sha1(data.azurerm_client_config.current.subscription_id), 0, 6)}"
}

resource "azurerm_virtual_network" "main" {
  count               = local.vm_count
  name                = "vnet-${local.prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  address_space       = ["10.20.0.0/24"]
  tags                = local.tags
}

resource "azurerm_subnet" "main" {
  count                = local.vm_count
  name                 = "snet-${local.prefix}"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main[0].name
  address_prefixes     = ["10.20.0.0/27"]
}

# Only SSH, only from your IP. Grafana is never exposed: reach it with an SSH tunnel (see docs/observability.md).
resource "azurerm_network_security_group" "main" {
  count               = local.vm_count
  name                = "nsg-${local.prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  tags                = local.tags

  security_rule {
    name                       = "ssh-from-my-ip"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = "${var.my_public_ip}/32"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "main" {
  count                     = local.vm_count
  subnet_id                 = azurerm_subnet.main[0].id
  network_security_group_id = azurerm_network_security_group.main[0].id
}

# Static: it is also the address eToro would see, so it can be allow-listed there if the API supports it.
resource "azurerm_public_ip" "main" {
  count               = local.vm_count
  name                = "pip-${local.prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  allocation_method   = "Static"
  sku                 = "Standard"
  tags                = local.tags
}

resource "azurerm_network_interface" "main" {
  count               = local.vm_count
  name                = "nic-${local.prefix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  tags                = local.tags

  ip_configuration {
    name                          = "internal"
    subnet_id                     = azurerm_subnet.main[0].id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.main[0].id
  }
}

resource "azurerm_linux_virtual_machine" "main" {
  count                           = local.vm_count
  name                            = "vm-${local.prefix}"
  resource_group_name             = azurerm_resource_group.main.name
  location                        = azurerm_resource_group.main.location
  size                            = var.vm_size
  admin_username                  = "deploy"
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.main[0].id]
  tags                            = local.tags

  admin_ssh_key {
    username   = "deploy"
    public_key = var.ssh_public_key
  }

  # Lets the machine read the vault and write the backups with no stored credentials.
  identity {
    type = "SystemAssigned"
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Premium_LRS"
    disk_size_gb         = 64
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest"
  }

  custom_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
    repo_url        = var.repo_url
    storage_account = local.storage_name
    container       = "db-backups"
  }))

  lifecycle {
    precondition {
      condition     = length(var.ssh_public_key) > 0
      error_message = "ssh_public_key is required when enable_vm = true (cat ~/.ssh/id_ed25519.pub)."
    }
  }
}

# --- Off-machine backups: the database dumps are copied here every night ------------------------------------
# A backup on the same disk as the database does not protect against losing the disk.

resource "azurerm_storage_account" "backups" {
  count                           = local.vm_count
  name                            = local.storage_name
  resource_group_name             = azurerm_resource_group.main.name
  location                        = azurerm_resource_group.main.location
  account_tier                    = "Standard"
  account_replication_type        = "GRS"
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false
  shared_access_key_enabled       = false # only Entra ID (the VM identity and you), no account keys
  tags                            = local.tags

  blob_properties {
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
    container_delete_retention_policy {
      days = 30
    }
  }

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
    ip_rules       = [var.my_public_ip, azurerm_public_ip.main[0].ip_address]
  }
}

resource "azurerm_storage_container" "backups" {
  count                 = local.vm_count
  name                  = "db-backups"
  storage_account_id    = azurerm_storage_account.backups[0].id
  container_access_type = "private"
  depends_on            = [azurerm_role_assignment.storage_owner]
}

resource "azurerm_storage_management_policy" "backups" {
  count              = local.vm_count
  storage_account_id = azurerm_storage_account.backups[0].id

  rule {
    name    = "expire-old-dumps"
    enabled = true
    filters {
      blob_types   = ["blockBlob"]
      prefix_match = ["db-backups/trading-"]
    }
    actions {
      base_blob {
        delete_after_days_since_modification_greater_than = 90
      }
    }
  }
}

# You manage the container; the VM only writes dumps (it cannot read other storage or delete the account).
resource "azurerm_role_assignment" "storage_owner" {
  count                = local.vm_count
  scope                = azurerm_storage_account.backups[0].id
  role_definition_name = "Storage Blob Data Owner"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "vm_backups" {
  count                = local.vm_count
  scope                = azurerm_storage_account.backups[0].id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_linux_virtual_machine.main[0].identity[0].principal_id
}

resource "azurerm_role_assignment" "vm_secrets" {
  count                = local.vm_count
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_linux_virtual_machine.main[0].identity[0].principal_id
}

variable "subscription_id" {
  description = "Azure subscription id (az account show --query id)"
  type        = string
}

variable "project" {
  description = "Short name used as a prefix for every resource"
  type        = string
  default     = "etfbot"
}

variable "environment" {
  description = "dev, paper or live"
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "paper", "live"], var.environment)
    error_message = "environment must be dev, paper or live."
  }
}

variable "location" {
  description = "Azure region"
  type        = string
  default     = "westeurope"
}

variable "db_admin_user" {
  description = "PostgreSQL administrator login"
  type        = string
  default     = "tradingadmin"
}

variable "db_admin_password" {
  description = "PostgreSQL administrator password. Pass it with TF_VAR_db_admin_password, never in a file."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.db_admin_password) >= 16
    error_message = "Use at least 16 characters."
  }
}

variable "db_sku_name" {
  description = "PostgreSQL size. B_Standard_B1ms is the smallest burstable one."
  type        = string
  default     = "B_Standard_B1ms"
}

variable "db_storage_mb" {
  description = "PostgreSQL storage in MB"
  type        = number
  default     = 32768
}

variable "my_public_ip" {
  description = "Your public IP, the only address allowed to reach the database (curl ifconfig.me)"
  type        = string
}

variable "secret_expiration_date" {
  description = "Expiry (RFC 3339) of the secrets stored in the Key Vault; rotate before it"
  type        = string
  default     = "2027-12-31T23:59:59Z"
}

variable "enable_vm" {
  description = "Create the VM that runs docker-compose, its network and the off-machine backup storage (costs money while it exists)"
  type        = bool
  default     = false
}

variable "vm_size" {
  description = "VM size. The whole stack (bot, PostgreSQL, Prometheus, Grafana, Tempo) needs about 3 GB of RAM: 4 GB is the minimum."
  type        = string
  default     = "Standard_B2s"
}

variable "ssh_public_key" {
  description = "Public key allowed to log in as user deploy (cat ~/.ssh/id_ed25519.pub). Required when enable_vm = true."
  type        = string
  default     = ""
}

variable "repo_url" {
  description = "Repository cloned onto the VM (public, so no credentials are needed)"
  type        = string
  default     = "https://github.com/adrianrevilla009/adri-trader.git"
}

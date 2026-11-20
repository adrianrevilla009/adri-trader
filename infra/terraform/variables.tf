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

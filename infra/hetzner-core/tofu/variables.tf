variable "project" {
  description = "Project name used in resource names and labels."
  type        = string
  default     = "reef"
}

variable "environment" {
  description = "Deployment environment name used in resource names and labels."
  type        = string
  default     = "prod"
}

variable "resource_name_override" {
  description = "Optional base name for cloud resources. Empty derives <project>-<environment>. Prefer setting this only at creation time."
  type        = string
  default     = ""
}

variable "server_name" {
  description = "Optional core server name. Empty derives <resource-name>-core-1. Prefer setting this only at creation time."
  type        = string
  default     = ""
}

variable "location" {
  description = "Hetzner Cloud location."
  type        = string
  default     = "nbg1"
}

variable "server_type" {
  description = "Hetzner Cloud server type."
  type        = string
  default     = "cx33"
}

variable "image" {
  description = "Hetzner Cloud server image."
  type        = string
  default     = "ubuntu-24.04"
}

variable "network_cidr" {
  description = "Private Hetzner network CIDR. Treat as a creation-time setting."
  type        = string
  default     = "10.70.0.0/16"

  validation {
    condition     = can(cidrhost(var.network_cidr, 0))
    error_message = "network_cidr must be a valid CIDR block."
  }
}

variable "network_zone" {
  description = "Hetzner network zone containing the private subnet."
  type        = string
  default     = "eu-central"
}

variable "subnet_cidr" {
  description = "Private Hetzner subnet CIDR. Treat as a creation-time setting."
  type        = string
  default     = "10.70.1.0/24"

  validation {
    condition     = can(cidrhost(var.subnet_cidr, 0))
    error_message = "subnet_cidr must be a valid CIDR block."
  }
}

variable "server_private_ip" {
  description = "Static address assigned to the core server inside subnet_cidr. Treat as a creation-time setting."
  type        = string
  default     = "10.70.1.10"

  validation {
    condition     = can(cidrnetmask("${var.server_private_ip}/32"))
    error_message = "server_private_ip must be a valid IPv4 address."
  }
}

variable "ops_user" {
  description = "Non-root SSH user created by cloud-init."
  type        = string
  default     = "ops"
}

variable "operator_ssh_host" {
  description = "Routine private SSH hostname or IP, normally the server's Tailscale MagicDNS name. Empty falls back to public IPv4 for initial bootstrap."
  type        = string
  default     = ""
}

variable "ssh_public_key_path" {
  description = "Local SSH public key installed for the ops user."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "admin_cidrs" {
  description = "CIDR blocks allowed to SSH to the core server when public SSH bootstrap/break-glass access is enabled."
  type        = list(string)

  validation {
    condition     = length(var.admin_cidrs) > 0
    error_message = "Set at least one admin CIDR so SSH is not open to the world."
  }
}

variable "enable_public_ssh" {
  description = "Open public SSH from admin_cidrs for initial bootstrap or break-glass recovery. Disable after private Tailscale SSH is verified."
  type        = bool
  default     = true
}

variable "api_domain" {
  description = "Optional public API domain served by Caddy if public web ingress is enabled later."
  type        = string
  default     = ""
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID used to manage the API DNS record. Leave empty to disable Cloudflare DNS."
  type        = string
  default     = ""
}

variable "cloudflare_account_id" {
  description = "Cloudflare account ID used to manage account-scoped resources such as R2 buckets. Leave empty to disable R2 bucket management."
  type        = string
  default     = ""
}

variable "cloudflare_dns_proxied" {
  description = "Proxy the API DNS record through Cloudflare. Keep false until origin TLS and public ingress are verified."
  type        = bool
  default     = false
}

variable "cloudflare_dns_ttl" {
  description = "TTL for non-proxied Cloudflare DNS records. Cloudflare uses automatic TTL when proxied."
  type        = number
  default     = 300
}

variable "r2_backup_bucket" {
  description = "Cloudflare R2 bucket name for encrypted backbone backups. Leave empty to disable R2 bucket management."
  type        = string
  default     = ""
}

variable "r2_backup_bucket_location" {
  description = "Optional Cloudflare R2 bucket location hint. Empty leaves Cloudflare default placement."
  type        = string
  default     = ""
}

variable "r2_backup_bucket_jurisdiction" {
  description = "Cloudflare R2 bucket jurisdiction."
  type        = string
  default     = "default"
}

variable "r2_backup_bucket_storage_class" {
  description = "Cloudflare R2 storage class for newly uploaded backup objects."
  type        = string
  default     = "Standard"
}

variable "enable_public_web" {
  description = "Open public HTTP/HTTPS firewall rules. Keep false for internal-only deployments."
  type        = bool
  default     = false
}

variable "enable_hetzner_backups" {
  description = "Enable Hetzner server backups for coarse whole-server rollback."
  type        = bool
  default     = true
}

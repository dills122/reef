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

variable "ops_user" {
  description = "Non-root SSH user created by cloud-init."
  type        = string
  default     = "ops"
}

variable "ssh_public_key_path" {
  description = "Local SSH public key installed for the ops user."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "admin_cidrs" {
  description = "CIDR blocks allowed to SSH to the core server."
  type        = list(string)

  validation {
    condition     = length(var.admin_cidrs) > 0
    error_message = "Set at least one admin CIDR so SSH is not open to the world."
  }
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

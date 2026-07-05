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

output "core_ipv4" {
  description = "Public IPv4 address of the core server."
  value       = hcloud_server.core.ipv4_address
}

output "server_name" {
  description = "Resolved core server name."
  value       = local.server_name
}

output "core_private_ipv4" {
  description = "Private IPv4 address of the core server on the Hetzner network."
  value       = hcloud_server_network.core.ip
}

output "network_cidr" {
  description = "Configured private Hetzner network CIDR."
  value       = var.network_cidr
}

output "subnet_cidr" {
  description = "Configured private Hetzner subnet CIDR."
  value       = var.subnet_cidr
}

output "ssh_command" {
  description = "Routine SSH command; uses operator_ssh_host when configured and public IPv4 only during bootstrap."
  value       = "ssh ${var.ops_user}@${local.operator_ssh_host}"
}

output "operator_ssh_host" {
  description = "Resolved routine operator SSH hostname or IP."
  value       = local.operator_ssh_host
}

output "public_ssh_command" {
  description = "Public IPv4 SSH command for initial bootstrap or break-glass access."
  value       = "ssh ${var.ops_user}@${hcloud_server.core.ipv4_address}"
}

output "public_ssh_enabled" {
  description = "Whether the Hetzner firewall currently permits public SSH from admin_cidrs."
  value       = var.enable_public_ssh
}

output "platform_runtime_tunnel_command" {
  description = "SSH tunnel command for private access to the platform runtime API."
  value       = "ssh -L 8080:127.0.0.1:8080 ${var.ops_user}@${local.operator_ssh_host}"
}

output "api_domain" {
  description = "Configured public API/admin domain."
  value       = var.api_domain
}

output "api_url" {
  description = "HTTPS URL for the public API/admin domain when configured."
  value       = var.api_domain == "" ? "" : "https://${var.api_domain}"
}

output "cloudflare_api_record" {
  description = "Cloudflare-managed API DNS record, if enabled."
  value       = length(cloudflare_dns_record.api) == 0 ? "" : cloudflare_dns_record.api[0].name
}

output "r2_backup_bucket" {
  description = "Cloudflare R2 backup bucket name, if enabled."
  value       = length(cloudflare_r2_bucket.backups) == 0 ? "" : cloudflare_r2_bucket.backups[0].name
}

output "r2_backup_endpoint" {
  description = "Cloudflare R2 S3-compatible endpoint for the backup bucket, if enabled."
  value = length(cloudflare_r2_bucket.backups) == 0 ? "" : format(
    "https://%s.%sr2.cloudflarestorage.com",
    var.cloudflare_account_id,
    local.r2_endpoint_prefix[var.r2_backup_bucket_jurisdiction]
  )
}

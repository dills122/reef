output "core_ipv4" {
  description = "Public IPv4 address of the core server."
  value       = hcloud_server.core.ipv4_address
}

output "core_private_ipv4" {
  description = "Private IPv4 address of the core server on the Hetzner network."
  value       = hcloud_server_network.core.ip
}

output "ssh_command" {
  description = "SSH command for the ops user."
  value       = "ssh ${var.ops_user}@${hcloud_server.core.ipv4_address}"
}

output "platform_runtime_tunnel_command" {
  description = "SSH tunnel command for private access to the platform runtime API."
  value       = "ssh -L 8080:127.0.0.1:8080 ${var.ops_user}@${hcloud_server.core.ipv4_address}"
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

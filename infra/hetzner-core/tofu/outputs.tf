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

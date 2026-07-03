output "droplet_id" {
  description = "DigitalOcean Droplet ID."
  value       = digitalocean_droplet.benchmark.id
}

output "droplet_name" {
  description = "DigitalOcean Droplet name."
  value       = digitalocean_droplet.benchmark.name
}

output "public_ipv4" {
  description = "Public IPv4 address for SSH."
  value       = digitalocean_droplet.benchmark.ipv4_address
}

output "ssh_user" {
  description = "SSH user created by cloud-init."
  value       = var.ssh_user
}

output "ssh_command" {
  description = "Convenience SSH command."
  value       = "ssh ${var.ssh_user}@${digitalocean_droplet.benchmark.ipv4_address}"
}

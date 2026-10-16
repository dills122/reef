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

output "volume_name" {
  description = "Name of the attached block-storage volume, or empty when none."
  value       = var.volume_size_gb > 0 ? digitalocean_volume.benchmark[0].name : ""
}

output "venue_private_ipv4" {
  description = "Private (VPC) address of the venue box, for the load box to target."
  value       = digitalocean_droplet.benchmark.ipv4_address_private
}

output "load_public_ipv4" {
  description = "Public IPv4 address of the load box for SSH, or empty in a single-box run."
  value       = local.two_box ? digitalocean_droplet.load[0].ipv4_address : ""
}

output "load_private_ipv4" {
  description = "Private (VPC) address of the load box, or empty in a single-box run."
  value       = local.two_box ? digitalocean_droplet.load[0].ipv4_address_private : ""
}

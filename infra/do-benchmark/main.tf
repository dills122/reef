locals {
  tags = distinct(var.tags)
}

resource "digitalocean_droplet" "benchmark" {
  image             = var.image
  name              = var.droplet_name
  region            = var.region
  size              = var.size
  monitoring        = var.monitoring
  backups           = false
  graceful_shutdown = true
  tags              = local.tags

  user_data = templatefile("${path.module}/cloud-init.yml.tftpl", {
    ssh_user       = var.ssh_user
    ssh_public_key = trimspace(var.ssh_public_key)
  })
}

resource "digitalocean_firewall" "benchmark" {
  name        = "${var.droplet_name}-fw"
  droplet_ids = [digitalocean_droplet.benchmark.id]
  tags        = local.tags

  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = var.allowed_ssh_cidrs
  }

  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
}

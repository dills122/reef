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

resource "digitalocean_volume" "benchmark" {
  count                   = var.volume_size_gb > 0 ? 1 : 0
  name                    = "${var.droplet_name}-vol"
  region                  = var.region
  size                    = var.volume_size_gb
  initial_filesystem_type = "ext4"
  description             = "Optional benchmark data volume; destroyed with the Droplet."
}

resource "digitalocean_volume_attachment" "benchmark" {
  count      = var.volume_size_gb > 0 ? 1 : 0
  droplet_id = digitalocean_droplet.benchmark.id
  volume_id  = digitalocean_volume.benchmark[0].id
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

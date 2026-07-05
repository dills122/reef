locals {
  labels = {
    project    = var.project
    env        = var.environment
    managed_by = "opentofu"
  }

  ssh_public_key = regex("^[^[:space:]]+[[:space:]]+[^[:space:]]+", trimspace(file(pathexpand(var.ssh_public_key_path))))
  resource_name  = "${var.project}-${var.environment}"

  public_web_cidrs = var.enable_public_web ? ["0.0.0.0/0", "::/0"] : []
}

resource "hcloud_ssh_key" "admin" {
  name       = "${local.resource_name}-admin"
  public_key = local.ssh_public_key
  labels     = local.labels

  lifecycle {
    ignore_changes = [name, labels]
  }
}

resource "hcloud_network" "main" {
  name     = "${local.resource_name}-net"
  ip_range = "10.70.0.0/16"
  labels   = local.labels
}

resource "hcloud_network_subnet" "main" {
  network_id   = hcloud_network.main.id
  type         = "cloud"
  network_zone = "eu-central"
  ip_range     = "10.70.1.0/24"
}

resource "hcloud_firewall" "core" {
  name   = "${local.resource_name}-core-fw"
  labels = local.labels

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = var.admin_cidrs
  }

  dynamic "rule" {
    for_each = local.public_web_cidrs

    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "80"
      source_ips = [rule.value]
    }
  }

  dynamic "rule" {
    for_each = local.public_web_cidrs

    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "443"
      source_ips = [rule.value]
    }
  }

  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = var.admin_cidrs
  }
}

resource "hcloud_server" "core" {
  name        = "${local.resource_name}-core-1"
  server_type = var.server_type
  image       = var.image
  location    = var.location

  ssh_keys     = [hcloud_ssh_key.admin.id]
  firewall_ids = [hcloud_firewall.core.id]
  backups      = var.enable_hetzner_backups
  labels       = local.labels

  user_data = templatefile("${path.module}/cloud-init.yaml.tftpl", {
    ops_user       = var.ops_user
    ssh_public_key = local.ssh_public_key
  })

  lifecycle {
    ignore_changes = [user_data]
  }
}

resource "hcloud_server_network" "core" {
  server_id  = hcloud_server.core.id
  network_id = hcloud_network.main.id
  ip         = "10.70.1.10"

  depends_on = [
    hcloud_network_subnet.main,
  ]
}

locals {
  labels = {
    project    = var.project
    env        = var.environment
    managed_by = "opentofu"
  }

  ssh_public_key = regex("^[^[:space:]]+[[:space:]]+[^[:space:]]+", trimspace(file(pathexpand(var.ssh_public_key_path))))
  resource_name  = "${var.project}-${var.environment}"

  public_web_cidrs      = var.enable_public_web ? ["0.0.0.0/0", "::/0"] : []
  manage_cloudflare_dns = var.cloudflare_zone_id != "" && var.api_domain != ""
  cloudflare_record_ttl = var.cloudflare_dns_proxied ? 1 : var.cloudflare_dns_ttl
  manage_r2_backups     = var.cloudflare_account_id != "" && var.r2_backup_bucket != ""
  r2_endpoint_prefix = {
    default = ""
    eu      = "eu."
    fedramp = "fedramp."
  }
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
    admin_cidrs    = var.admin_cidrs
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

resource "cloudflare_dns_record" "api" {
  count = local.manage_cloudflare_dns ? 1 : 0

  zone_id = var.cloudflare_zone_id
  name    = var.api_domain
  type    = "A"
  content = hcloud_server.core.ipv4_address
  ttl     = local.cloudflare_record_ttl
  proxied = var.cloudflare_dns_proxied
  comment = "${var.project} ${var.environment} Hetzner backbone API/admin"
}

resource "cloudflare_r2_bucket" "backups" {
  count = local.manage_r2_backups ? 1 : 0

  account_id    = var.cloudflare_account_id
  name          = var.r2_backup_bucket
  location      = var.r2_backup_bucket_location == "" ? null : var.r2_backup_bucket_location
  jurisdiction  = var.r2_backup_bucket_jurisdiction
  storage_class = var.r2_backup_bucket_storage_class
}

variable "do_token" {
  description = "DigitalOcean API token used by the provider."
  type        = string
  sensitive   = true
}

variable "droplet_name" {
  description = "Name for the benchmark Droplet."
  type        = string
  default     = "reef-stream-ack-benchmark"
}

variable "region" {
  description = "DigitalOcean region slug."
  type        = string
  default     = "sfo2"
}

variable "size" {
  description = "DigitalOcean Droplet size slug."
  type        = string
  default     = "c-8"
}

variable "image" {
  description = "DigitalOcean image slug."
  type        = string
  default     = "ubuntu-24-04-x64"
}

variable "ssh_user" {
  description = "Non-root SSH user created by cloud-init."
  type        = string
  default     = "reefbench"
}

variable "ssh_public_key" {
  description = "SSH public key content installed for the benchmark user."
  type        = string
}

variable "allowed_ssh_cidrs" {
  description = "CIDR blocks allowed to SSH to the benchmark Droplet."
  type        = list(string)
}

variable "tags" {
  description = "Tags applied to benchmark resources."
  type        = list(string)
  default     = ["reef", "benchmark", "stream-ack"]
}

variable "monitoring" {
  description = "Enable DigitalOcean monitoring on the Droplet."
  type        = bool
  default     = true
}

ui = true

api_addr = "http://127.0.0.1:8200"

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = 1
}

storage "postgresql" {}

audit "file" "file" {
  description = "Reef OpenBao audit log"

  options {
    file_path = "/bao/logs/audit.log"
  }
}

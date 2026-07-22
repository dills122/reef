#!/usr/bin/env bash
set -euo pipefail

mode="${1:-bootstrap}"
tailscale_hostname="${TAILSCALE_HOSTNAME:-$(hostname -s)}"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "run as root (for example: sudo $0 $mode)" >&2
    exit 1
  fi
}

show_status() {
  systemctl is-enabled tailscaled
  systemctl is-active tailscaled
  tailscale status
  printf 'tailscale_ipv4=%s\n' "$(tailscale ip -4)"
  ufw status | grep -E '(^Status:|tailscale0.*22/tcp|22/tcp.*tailscale0)' || true
}

case "$mode" in
  bootstrap)
    require_root
    if ! command -v tailscale >/dev/null 2>&1; then
      installer="$(mktemp)"
      trap 'rm -f "$installer"' EXIT
      curl -fsSL https://tailscale.com/install.sh -o "$installer"
      sh "$installer"
    fi

    systemctl enable --now tailscaled
    ufw allow in on tailscale0 to any port 22 proto tcp comment 'Reef SSH over Tailscale'

    if tailscale status >/dev/null 2>&1; then
      tailscale set --hostname="$tailscale_hostname"
    else
      echo "Authenticate this host with the URL printed below. No auth key is stored by Reef."
      tailscale up --hostname="$tailscale_hostname"
    fi

    show_status
    ;;
  status)
    show_status
    ;;
  *)
    echo "usage: $0 [bootstrap|status]" >&2
    exit 2
    ;;
esac

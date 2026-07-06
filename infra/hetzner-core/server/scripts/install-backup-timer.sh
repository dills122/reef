#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
BACKUP_USER="${REEF_BACKUP_USER:-ops}"
SCHEDULE="${REEF_BACKUP_SCHEDULE:-*-*-* 03:15:00 UTC}"

if [[ ! -f "$BASE/secrets/backup.env" ]]; then
  echo "missing $BASE/secrets/backup.env" >&2
  exit 1
fi

sudo tee /etc/systemd/system/reef-backup.service >/dev/null <<EOF
[Unit]
Description=Reef encrypted DB backup
Wants=docker.service
After=docker.service

[Service]
Type=oneshot
User=${BACKUP_USER}
WorkingDirectory=${BASE}
ExecStart=${BASE}/scripts/backup-dbs.sh
EOF

sudo tee /etc/systemd/system/reef-backup.timer >/dev/null <<EOF
[Unit]
Description=Run Reef encrypted DB backup

[Timer]
OnCalendar=${SCHEDULE}
Persistent=true
RandomizedDelaySec=15m

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now reef-backup.timer
systemctl list-timers reef-backup.timer

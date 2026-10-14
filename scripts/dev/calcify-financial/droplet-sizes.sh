#!/usr/bin/env bash
# droplet-sizes.sh OUTFILE -- every 2 s, append the size in KiB of the Redpanda and Postgres data directories of the running containers ("redpanda KB" / "postgres KB"). Used by droplet-chain-run.sh to size a tmpfs data volume.
out="$1"; : > "$out"
while sleep 2; do
  for c in $(docker ps -q); do
    img="$(docker inspect -f '{{.Config.Image}}' "$c" 2>/dev/null)"
    case "$img" in
      *redpanda*) kb="$(docker exec "$c" du -sk /var/lib/redpanda/data 2>/dev/null | cut -f1)"; [ -n "$kb" ] && echo "redpanda $kb" >> "$out" ;;
      *postgres*) kb="$(docker exec "$c" du -sk /var/lib/postgresql/data 2>/dev/null | cut -f1)"; [ -n "$kb" ] && echo "postgres $kb" >> "$out" ;;
    esac
  done
done

#!/usr/bin/env bash
# droplet-sizes.sh OUTFILE -- every 2 s, append the size in KiB of the Redpanda and Postgres data directories of the running containers ("redpanda KB" / "postgres KB"). Used by droplet-chain-run.sh to size a tmpfs data volume.
# Best-effort sampler: -u and pipefail catch typos, but no -e, because a container can vanish mid-sample and `docker exec`/`inspect` failures must not stop the loop.
set -uo pipefail
out="$1"; : > "$out"
# Never outlive a forgotten run: stop after SIZES_MAX_SECONDS (default 1800) even if nobody kills it.
deadline=$(( $(date +%s) + ${SIZES_MAX_SECONDS:-1800} ))
# SIZES_IGNORE_EXISTING=1 skips containers that were already running when the sampler started (a shared laptop).
ignore=" "; [ "${SIZES_IGNORE_EXISTING:-0}" = 1 ] && ignore=" $(docker ps -q | tr '\n' ' ') "
while sleep 2; do
  [ "$(date +%s)" -lt "$deadline" ] || exit 0
  for c in $(docker ps -q); do
    case "$ignore" in *" $c "*) continue ;; esac
    img="$(docker inspect -f '{{.Config.Image}}' "$c" 2>/dev/null)"
    case "$img" in
      *redpanda*)
        kb="$(docker exec "$c" du -sk /var/lib/redpanda/data 2>/dev/null | cut -f1)"; [ -n "$kb" ] && echo "redpanda $kb" >> "$out"
        docker exec "$c" sh -c 'du -sk /var/lib/redpanda/data/kafka/* 2>/dev/null' | awk '{n=$2; sub(".*/","",n); print "topic:" n " " $1}' >> "$out" ;;
      *postgres*) kb="$(docker exec "$c" du -sk /var/lib/postgresql/data 2>/dev/null | cut -f1)"; [ -n "$kb" ] && echo "postgres $kb" >> "$out" ;;
    esac
  done
done

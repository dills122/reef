#!/usr/bin/env bash
# One time-boxed run of the finance chain spike (S4EndToEndSpikeTest) on a quiet dedicated DigitalOcean droplet, owner-approved 2026-10-14.
# It provisions the existing do-benchmark stack, ships ONLY tracked files (git archive of HEAD: never .env, .planning, .mcp.json or other untracked files),
# installs a JDK on the host, runs a short matrix, fetches the outputs, and destroys the droplet on every exit path.
#
#   REEF_DO_SIZE=c-16 scripts/dev/calcify-financial/droplet-chain-run.sh [matrix]    (matrix: default | compare | tmpfs | baseline | smoke | s1smoke)
#   `compare` runs the single-thread microbenchmarks first, then lanes 1 to 4 with the projector and lanes 1 and 3 without it, all with -XX:+UseParallelGC, then the S1 shard cases.
#
# Needs DIGITALOCEAN_TOKEN in .env or the environment, an SSH key as the harness expects, and REEF_DO_CONFIRM_DESTROYABLE=1 is set here.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HARNESS="$ROOT_DIR/scripts/dev/do-benchmark-host.sh"
INFRA_DIR="$ROOT_DIR/infra/do-benchmark"
MATRIX="${1:-default}"
OUT_DIR="${REEF_DROPLET_OUT_DIR:-$ROOT_DIR/reports/do-benchmark/chain-run-$(date -u +%Y%m%dT%H%M%SZ)}"
REMOTE_DIR=reef-chain
DEADLINE_SECONDS="${REEF_DROPLET_DEADLINE_SECONDS:-6000}"
START="$(date +%s)"
export REEF_DO_CONFIRM_DESTROYABLE=1
export REEF_DO_SIZE="${REEF_DO_SIZE:-c-16}"
# REEF_DO_VOLUME_GB=N attaches an N GB block-storage volume (mounted at /mnt/pgvol, Postgres data directory /mnt/pgvol/pg); the harness destroy removes it with the droplet.
VOLUME_GB="${REEF_DO_VOLUME_GB:-0}"
export TF_VAR_volume_size_gb="$VOLUME_GB"

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
elapsed() { echo $(( $(date +%s) - START )); }

destroyed=0
cleanup() {
  local status=$?
  if [ "$destroyed" -eq 0 ]; then
    log "destroying the droplet (exit status $status)"
    "$HARNESS" destroy || log "DESTROY FAILED: check 'doctl compute droplet list' and destroy by hand"
    destroyed=1
  fi
}
trap cleanup EXIT INT TERM

outputs() { (cd "$INFRA_DIR" && tofu output -raw "$1"); }

# Run a remote command; the droplet user is created by cloud-init and is in the docker group.
remote() {
  ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" -o ServerAliveInterval=30 "$USER_NAME@$HOST" "$@"
}

if [ "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=no | wc -l | tr -d ' ')" != 0 ]; then
  echo "tracked changes present; run from a clean checkout so the shipped tree is exactly HEAD" >&2; exit 1
fi
HEAD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
mkdir -p "$OUT_DIR"
log "head $HEAD_SHA; size $REEF_DO_SIZE; matrix $MATRIX; outputs in $OUT_DIR; deadline ${DEADLINE_SECONDS}s"

log "provisioning"
# The harness waits ten minutes for cloud-init. DigitalOcean's apt mirror has been seen mid-sync ("File has unexpected size"), which fails the bootstrap; tolerate that here and repair it below.
"$HARNESS" up || log "harness up did not report a ready host; checking the bootstrap by hand"
HOST="$(outputs public_ipv4)"; USER_NAME="$(outputs ssh_user)"
KEY="${REEF_DO_SSH_PRIVATE_KEY:-${REEF_DO_SSH_PUBLIC_KEY:-$HOME/.ssh/id_ed25519.pub}}"; KEY="${KEY%.pub}"
log "host $USER_NAME@$HOST"

if ! remote "sudo test -f /var/log/reef-benchmark-bootstrap.done"; then
  log "bootstrap marker missing: pointing apt at archive.ubuntu.com and re-running the cloud-init commands"
  remote 'sudo sed -i "s#http://mirrors.digitalocean.com/ubuntu#http://archive.ubuntu.com/ubuntu#g" /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null; sudo bash -c "nohup bash /var/lib/cloud/instance/scripts/runcmd > /var/log/bootstrap-retry.log 2>&1 &"; true'
  for _ in $(seq 1 120); do
    if remote "sudo test -f /var/log/reef-benchmark-bootstrap.done" 2>/dev/null; then break; fi
    sleep 5
  done
  remote "sudo test -f /var/log/reef-benchmark-bootstrap.done" || { log "bootstrap still not done after the repair; giving up (the droplet is destroyed on exit)"; exit 1; }
fi
log "host bootstrap ready"

if [ "$VOLUME_GB" -gt 0 ]; then
  VOLNAME="$(outputs volume_name)"
  log "mounting volume $VOLNAME ($VOLUME_GB GB) at /mnt/pgvol"
  remote "sudo mkdir -p /mnt/pgvol && sudo mount -o noatime,discard /dev/disk/by-id/scsi-0DO_Volume_$VOLNAME /mnt/pgvol && sudo mkdir -p /mnt/pgvol/pg /mnt/pgvol/rp && sudo chown 70:70 /mnt/pgvol/pg && sudo chown 101:101 /mnt/pgvol/rp && df -h /mnt/pgvol | tail -1 && for i in 1 2; do dd if=/dev/zero of=/mnt/pgvol/fs.test bs=4k count=200 oflag=dsync 2>&1 | tail -1; done; sudo rm -f /mnt/pgvol/fs.test" | tee "$OUT_DIR/volume.txt"
fi

log "shipping tracked files only (git archive HEAD)"
git -C "$ROOT_DIR" archive --format=tar HEAD | remote "mkdir -p $REMOTE_DIR && tar -x -C $REMOTE_DIR"

log "installing a JDK and recording the host"
remote "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless >/dev/null && java -version 2>&1 | head -1 && nproc && lscpu | grep -E 'Model name|^CPU\\(s\\)' && free -g | head -2 && docker --version && uptime" | tee "$OUT_DIR/host.txt"

log "root disk synchronous-write check (200 x 4 KiB, dsync)"
{ remote "df -h / | tail -1; for i in 1 2; do dd if=/dev/zero of=/tmp/fs.test bs=4k count=200 oflag=dsync 2>&1 | tail -1; done; rm -f /tmp/fs.test"; } 2>&1 | tee "$OUT_DIR/rootdisk.txt" || true

log "microbenchmarks (single thread SHA-256 and memory, then a thread-scaling loop)"
scp -i "$KEY" -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" "$ROOT_DIR"/scripts/dev/calcify-financial/droplet-microbench/*.java "$USER_NAME@$HOST:/tmp/" >/dev/null
scp -i "$KEY" -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" "$ROOT_DIR/scripts/dev/calcify-financial/droplet-sizes.sh" "$USER_NAME@$HOST:/tmp/" >/dev/null
{ remote "java /tmp/Bench.java && java /tmp/Par.java"; } 2>&1 | tee "$OUT_DIR/microbench.txt" || log "microbenchmarks failed"

run_case() {
  # run_case LABEL ENV...   -- each case runs the S4 test once with 100,000 trades per lane and keeps its output
  local label="$1"; shift
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  log "case $label ($*)"
  remote "sudo rm -rf /mnt/pgvol/pg/* /mnt/pgvol/rp/* 2>/dev/null; (bash /tmp/droplet-sizes.sh /tmp/sizes-$label.txt & echo \$! > /tmp/sizes.pid) ; cd $REMOTE_DIR/services/platform-runtime && env S4_TRADES=${S4_TRADES:-100000} $* S0_OUT_DIR=/tmp/s4out S4_ACCOUNTS=1000 S4_PG=nobindingfk timeout 1500 ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*S4EndToEndSpikeTest.wholeChainOnOneMachine' -i --no-daemon 2>&1 | grep -E '^ +S4 |lanes together|projectors together|CPU per decision|  gc |  lane |S4-|FAILED|AssertionFailed|BUILD' ; kill \$(cat /tmp/sizes.pid) 2>/dev/null; grep '^topic:' /tmp/sizes-$label.txt | sort -k1,1 -k2,2nr | awk '!seen[\$1]++' | sed 's/^/MAX-TOPIC-KIB /'; for d in redpanda postgres; do echo \"MAX-DATA-DIR \$d KiB: \$(grep \"^\$d \" /tmp/sizes-$label.txt | cut -d' ' -f2 | sort -n | tail -1)\"; done; docker ps -a --format 'LEFTOVER {{.Names}}'" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
}

run_s1() {
  # run_s1 LABEL ENV...   -- the S1 instrument-shard prototype (ample cash, so no cross-shard cash moves), 300,000 trades over 1,000 participants
  local label="$1"; shift
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  log "case $label ($*)"
  remote "cd $REMOTE_DIR/services/platform-runtime && env $* S0_OUT_DIR=/tmp/s4out S1_PARTICIPANTS=1000 S1_TRADES=300000 S1_CASH_TRADES=1000 timeout 1500 ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*S4EndToEndSpikeTest.shardedByInstrument' -i --no-daemon 2>&1 | grep -E '^ +S1 |planned:|lanes:|moves planned|machine load|FAILED|AssertionFailed|BUILD' ; docker ps -a --format 'LEFTOVER {{.Names}}'" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
}

: > "$OUT_DIR/matrix.txt"
case "$MATRIX" in
  compare)
    PG=JAVA_TOOL_OPTIONS=-XX:+UseParallelGC
    run_case "lanes1" $PG S4_LANES=1
    run_case "lanes1-noproject" $PG S4_LANES=1 S4_PROJECT=false
    run_case "lanes2" $PG S4_LANES=2
    run_case "lanes3" $PG S4_LANES=3
    run_case "lanes3-noproject" $PG S4_LANES=3 S4_PROJECT=false
    run_case "lanes4" $PG S4_LANES=4
    run_case "lanes5" $PG S4_LANES=5
    run_case "lanes1-g1" S4_LANES=1
    run_case "lanes1-again" $PG S4_LANES=1
    run_s1 "s1-shards1" $PG S1_SHARDS=1
    run_s1 "s1-shards2" $PG S1_SHARDS=2
    run_s1 "s1-shards3" $PG S1_SHARDS=3
    ;;
  tmpfs)
    # Option A of the capacity plan: broker and Postgres data on tmpfs (not durable; a lost host means rerun from the captured inputs), ParallelGC, projector on.
    T="JAVA_TOOL_OPTIONS=-XX:+UseParallelGC S4_PG_DOCKER=--tmpfs=/var/lib/postgresql/data:rw,size=6g,mode=1777 S4_RP_DOCKER=--tmpfs=/var/lib/redpanda/data:rw,size=6g,uid=101,gid=101,mode=0777"
    run_case "tmpfs-lanes2" $T S4_LANES=2
    run_case "tmpfs-lanes3" $T S4_LANES=3
    run_case "tmpfs-lanes1" $T S4_LANES=1
    run_case "tmpfs-lanes4" $T S4_LANES=4
    run_case "tmpfs-lanes2-again" $T S4_LANES=2
    ;;
  baseline)
    # Arena baseline (owner decisions 2026-10-14): one server, everything on a durable disk, full retention, projector on, ParallelGC. REEF_BASELINE_STORE=root (default) or volume (broker and Postgres on the attached volume);
    # REEF_BASELINE_LANES is the lane counts to try; the sustained case runs the middle count with more trades.
    G="JAVA_TOOL_OPTIONS=-XX:+UseParallelGC"
    if [ "${REEF_BASELINE_STORE:-root}" = volume ]; then G="$G S4_PG_DOCKER=--volume=/mnt/pgvol/pg:/var/lib/postgresql/data S4_RP_DOCKER=--volume=/mnt/pgvol/rp:/var/lib/redpanda/data"; fi
    if [ "${REEF_BASELINE_STORE:-root}" = tmpfs ]; then G="$G S4_PG_DOCKER=--tmpfs=/var/lib/postgresql/data:rw,size=12g,mode=1777 S4_RP_DOCKER=--tmpfs=/var/lib/redpanda/data:rw,size=24g,uid=101,gid=101,mode=0777"; fi
    LANES_LIST=(${REEF_BASELINE_LANES:-4 6 8})
    for n in "${LANES_LIST[@]}"; do run_case "base-l$n" $G S4_LANES=$n; done
    MID="${LANES_LIST[$(( ${#LANES_LIST[@]} / 2 ))]}"
    S4_TRADES=250000 run_case "sustained-l$MID" $G S4_LANES=$MID
    run_case "base-l$MID-again" $G S4_LANES=$MID
    # Is one broker core the limit? Same lanes with a four-core broker (only meaningful with the stores off the slow disk).
    if [ "${REEF_BASELINE_STORE:-root}" != root ]; then run_case "base-l$MID-smp4" $G S4_LANES=$MID S4_SMP=4 S4_BROKER_MEM=4G; fi
    ;;
  smoke) run_case "smoke-1lane" S4_LANES=1 S4_TRADES=5000 ;;
  s1smoke) run_s1 "s1-smoke" S1_SHARDS=2 ;;
  *)
    run_case "lanes1-a" S4_LANES=1
    run_case "lanes2-a" S4_LANES=2
    run_case "lanes3-a" S4_LANES=3
    run_case "lanes4-a" S4_LANES=4
    run_case "lanes1-b" S4_LANES=1
    run_case "lanes2-b" S4_LANES=2
    run_case "lanes3-b" S4_LANES=3
    run_case "lanes3-smp2" S4_LANES=3 S4_SMP=2 S4_BROKER_MEM=2G
    run_case "lanes2-free" S4_LANES=2 S4_FREE=true S4_VERIFY_ASYNC=true
    run_s1 "s1-shards1" S1_SHARDS=1
    run_s1 "s1-shards2" S1_SHARDS=2
    run_s1 "s1-shards3" S1_SHARDS=3
    run_s1 "s1-shards4" S1_SHARDS=4
    ;;
esac

log "fetching the metrics file"
remote "cat /tmp/s4out/s0-metrics.properties 2>/dev/null; cat /tmp/s4out/s4-end-to-end.txt 2>/dev/null" > "$OUT_DIR/metrics-and-last-log.txt" || true
log "done in $(elapsed)s; the droplet is destroyed on exit"

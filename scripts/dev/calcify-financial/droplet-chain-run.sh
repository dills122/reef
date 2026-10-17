#!/usr/bin/env bash
# Time-boxed Calcify capacity runs on a quiet dedicated DigitalOcean droplet. Every run is paid and needs the owner's explicit go with size and cost stated in chat.
# It provisions the existing do-benchmark stack, ships ONLY tracked files (git archive of HEAD: never .env, .planning, .mcp.json or other untracked files),
# installs a JDK on the host, runs a short matrix, fetches the outputs, and destroys the droplet on every exit path.
#
#   REEF_DO_SIZE=g5-16vcpu-64gb-50gb REEF_DO_REGION=atl1 scripts/dev/calcify-financial/droplet-chain-run.sh [matrix]
# Matrices (the supported ones; the S4/S1-era spike matrices were archived to Reef Records on 2026-10-16, see docs/RECORDS_RETENTION.md):
#   `fullchain` / `fullchainsmoke` (docs/work/CALCIFY_CAPACITY_RUNTIME.md, "Full-chain paced run") run the whole path on the host on the validated 10k ingress profile
#   (calcify-direct-bootstrap.mjs: Netty, in-memory intake with a durable Redpanda ack, 16 lanes, standing makers) with the data volumes on tmpfs, as a ladder of paced rates
#   (REEF_FC_RATES, default "2500 5000 7500 10000" booked trades/s, REEF_FC_SECONDS each, default 60) and optionally one long hold (REEF_FC_LONG_RATE, REEF_FC_LONG_SECONDS, default 900);
#   the smoke is 20 s at 500/s. Needs RAM for the tmpfs limits (REEF_FC_TMPFS_BOUNDARY_GB 40, REEF_FC_TMPFS_BROKER_GB 24). Knobs: REEF_FC_MODE, REEF_FC_EVICT, REEF_FC_QUANTITY (64 instruments over 16 lanes, fixed by the bootstrap).
#   `soak` / `soaksmoke` (docs/work/CALCIFY_SOAK_HARNESS.md) hold 10,000 trades/s for 15 and 30 minutes on the finance lane and projection only, in capture-settle-v1 mode.
#   `lane` (CAL-FIN-P23) measures the engine lane's drain rate by batch size against a tmpfs Redpanda, then the finance lane cases; `enginesmoke` is a few seconds of the engine drain.
#
# Needs DIGITALOCEAN_TOKEN in .env or the environment, an SSH key as the harness expects, and REEF_DO_CONFIRM_DESTROYABLE=1 is set here.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HARNESS="$ROOT_DIR/scripts/dev/do-benchmark-host.sh"
INFRA_DIR="$ROOT_DIR/infra/do-benchmark"
MATRIX="${1:-fullchainsmoke}"
OUT_DIR="${REEF_DROPLET_OUT_DIR:-$ROOT_DIR/reports/do-benchmark/chain-run-$(date -u +%Y%m%dT%H%M%SZ)}"
REMOTE_DIR=reef-chain
# One source of truth for the Redpanda image: compose.local.yml (what every local/compose benchmark runs). The engine-drain case below used a hardcoded older tag.
RP_IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(redpandadata\/redpanda:[^[:space:]]*\).*/\1/p' "$ROOT_DIR/compose.local.yml" | head -n 1)"
[ -n "$RP_IMAGE" ] || { echo "cannot read the redpanda image from compose.local.yml" >&2; exit 1; }
# The soak matrix runs four cases of 15 to 30 minutes each (about 100 minutes of load plus drains and builds); every other matrix is a few short cases.
case "$MATRIX" in soak) DEFAULT_DEADLINE_SECONDS=14400 ;; *) DEFAULT_DEADLINE_SECONDS=6000 ;; esac
DEADLINE_SECONDS="${REEF_DROPLET_DEADLINE_SECONDS:-$DEFAULT_DEADLINE_SECONDS}"
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
    log "destroying the droplet and its volume, if any (exit status $status)"
    if "$HARNESS" destroy || { log "destroy failed once; retrying"; sleep 10; "$HARNESS" destroy; }; then
      destroyed=1
    else
      log "DESTROY FAILED: check 'doctl compute droplet list' and 'doctl compute volume list' (a volume named reef-stream-ack-benchmark-vol may remain) and destroy by hand"
      exit 1                                                  # a failed teardown must not look like a clean run
    fi
  fi
}
trap cleanup EXIT INT TERM

outputs() { (cd "$INFRA_DIR" && tofu output -raw "$1"); }

# Run a remote command; the droplet user is created by cloud-init and is in the docker group.
remote() {
  ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" -o ServerAliveInterval=30 "$USER_NAME@$HOST" "$@"
}

# Untracked files are deliberately allowed (the owner's local notes and .env live there): `git archive HEAD` below ships tracked files only, so the shipped tree is exactly HEAD.
if [ "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=no | wc -l | tr -d ' ')" != 0 ]; then
  echo "tracked changes present; run from a clean checkout so the shipped tree is exactly HEAD" >&2; exit 1
fi
# The private key is the public key path without .pub; refuse to rent a host this script cannot log in to.
KEY="${REEF_DO_SSH_PRIVATE_KEY:-${REEF_DO_SSH_PUBLIC_KEY:-$HOME/.ssh/id_ed25519.pub}}"; KEY="${KEY%.pub}"
[ -r "$KEY" ] || { echo "missing or unreadable SSH private key: $KEY (set REEF_DO_SSH_PRIVATE_KEY)" >&2; exit 1; }
HEAD_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
mkdir -p "$OUT_DIR"
log "head $HEAD_SHA; size $REEF_DO_SIZE; matrix $MATRIX; outputs in $OUT_DIR; deadline ${DEADLINE_SECONDS}s"

log "provisioning"
# The harness waits ten minutes for cloud-init. DigitalOcean's apt mirror has been seen mid-sync ("File has unexpected size"), which fails the bootstrap; tolerate that here and repair it below.
"$HARNESS" up || log "harness up did not report a ready host; checking the bootstrap by hand"
HOST="$(outputs public_ipv4)"; USER_NAME="$(outputs ssh_user)"
log "host $USER_NAME@$HOST"

if ! remote "sudo test -f /var/log/reef-benchmark-bootstrap.done"; then
  log "bootstrap marker missing: pointing apt at archive.ubuntu.com and re-running the cloud-init commands"
  remote 'sudo test -x /var/lib/cloud/instance/scripts/runcmd || { echo "cloud-init runcmd script is missing" >&2; exit 1; }; sudo sed -i "s#http://mirrors.digitalocean.com/ubuntu#http://archive.ubuntu.com/ubuntu#g" /etc/apt/sources.list.d/*.sources /etc/apt/sources.list 2>/dev/null; sudo bash -c "nohup bash /var/lib/cloud/instance/scripts/runcmd > /var/log/bootstrap-retry.log 2>&1 &"; true'
  for _ in $(seq 1 120); do
    if remote "sudo test -f /var/log/reef-benchmark-bootstrap.done" 2>/dev/null; then break; fi
    sleep 5
  done
  remote "sudo test -f /var/log/reef-benchmark-bootstrap.done" || { log "bootstrap still not done after the repair; giving up (the droplet is destroyed on exit)"; exit 1; }
fi
log "host bootstrap ready"

if [ "$VOLUME_GB" -gt 0 ]; then
  VOLNAME="$(outputs volume_name)"
  [ -n "$VOLNAME" ] || { log "the volume output is empty: refusing to mount"; exit 1; }
  log "mounting volume $VOLNAME ($VOLUME_GB GB) at /mnt/pgvol"
  # The attachment can lag the Tofu apply: wait for the device node before mounting.
  remote "for _ in \$(seq 1 60); do test -e /dev/disk/by-id/scsi-0DO_Volume_$VOLNAME && break; sleep 2; done; test -e /dev/disk/by-id/scsi-0DO_Volume_$VOLNAME" || { log "volume device did not appear"; exit 1; }
  remote "sudo mkdir -p /mnt/pgvol && sudo mount -o noatime,discard /dev/disk/by-id/scsi-0DO_Volume_$VOLNAME /mnt/pgvol && sudo mkdir -p /mnt/pgvol/pg /mnt/pgvol/rp && sudo chown 70:70 /mnt/pgvol/pg && sudo chown 101:101 /mnt/pgvol/rp && df -h /mnt/pgvol | tail -1 && for i in 1 2; do dd if=/dev/zero of=/mnt/pgvol/fs.test bs=4k count=200 oflag=dsync 2>&1 | tail -1; done; sudo rm -f /mnt/pgvol/fs.test" | tee "$OUT_DIR/volume.txt"
  # A silent fall back to the root disk would invalidate every volume result: refuse to run the matrix without the mount.
  remote "mountpoint -q /mnt/pgvol && test -d /mnt/pgvol/pg && test -d /mnt/pgvol/rp" || { log "volume mount verification failed"; exit 1; }
fi

log "shipping tracked files only (git archive HEAD)"
git -C "$ROOT_DIR" archive --format=tar HEAD | remote "mkdir -p $REMOTE_DIR && tar -x -C $REMOTE_DIR"

log "installing a JDK and recording the host"
remote "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless >/dev/null && mkdir -p ~/.gradle && printf 'org.gradle.jvmargs=-Xmx4g\nkotlin.daemon.jvmargs=-Xmx4g\n' > ~/.gradle/gradle.properties && java -version 2>&1 | head -1 && nproc && lscpu | grep -E 'Model name|^CPU\\(s\\)' && free -g | head -2 && docker --version && uptime" | tee "$OUT_DIR/host.txt"

log "root disk synchronous-write check (200 x 4 KiB, dsync)"
{ remote "df -h / | tail -1; for i in 1 2; do dd if=/dev/zero of=/tmp/fs.test bs=4k count=200 oflag=dsync 2>&1 | tail -1; done; rm -f /tmp/fs.test"; } 2>&1 | tee "$OUT_DIR/rootdisk.txt" || true

log "microbenchmarks (single thread SHA-256 and memory, then a thread-scaling loop)"
scp -i "$KEY" -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" "$ROOT_DIR"/scripts/dev/calcify-financial/droplet-microbench/*.java "$USER_NAME@$HOST:/tmp/" >/dev/null
scp -i "$KEY" -o UserKnownHostsFile="$ROOT_DIR/.tmp-do-known-hosts" "$ROOT_DIR/scripts/dev/calcify-financial/droplet-sizes.sh" "$USER_NAME@$HOST:/tmp/" >/dev/null
{ remote "java /tmp/Bench.java && java /tmp/Par.java"; } 2>&1 | tee "$OUT_DIR/microbench.txt" || log "microbenchmarks failed"

run_engine_drain() {
  # run_engine_drain LABEL PAIRS BATCHES [ENV...]  -- the Go lane drain test (real source, real transactional publisher) against a Redpanda whose data directory is tmpfs.
  # The Go toolchain runs in the golang image matching go.mod so the host needs no Go; the event topic's max.message.bytes and the producer limit are raised by the test itself.
  # Redpanda is sized from the host (half the cores up to 4, a quarter of the memory up to 8 GB) so the same case runs on a small smoke droplet and on the measurement host.
  local label="$1" pairs="$2" batches="$3"; shift 3
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  log "case $label (pairs $pairs batches $batches $*)"
  remote "docker rm -f -v reef-it-rp >/dev/null 2>&1; NP=\$(nproc); SMP=\$(( NP / 2 )); [ \$SMP -gt 4 ] && SMP=4; [ \$SMP -lt 1 ] && SMP=1; MEMG=\$(( \$(free -g | awk '/^Mem:/{print \$2}') / 4 )); [ \$MEMG -gt 8 ] && MEMG=8; [ \$MEMG -lt 1 ] && MEMG=1; docker run -d --rm --name reef-it-rp --network host --tmpfs /var/lib/redpanda/data:rw,size=\${MEMG}g,uid=101,gid=101,mode=0777 $RP_IMAGE redpanda start --mode dev-container --smp \$SMP --memory \${MEMG}G --reserve-memory 0M --overprovisioned --node-id 0 --check=false --kafka-addr 0.0.0.0:19092 --advertise-kafka-addr 127.0.0.1:19092 >/dev/null && echo \"redpanda smp=\$SMP memory=\${MEMG}G\"; ready=0; for i in \$(seq 1 60); do docker exec reef-it-rp rpk cluster info >/dev/null 2>&1 && ready=1 && break; sleep 2; done; [ \$ready = 1 ] || { echo 'REDPANDA-NOT-READY'; docker logs --tail 15 reef-it-rp 2>&1; }; GOV=\$(awk '/^go /{print \$2}' $REMOTE_DIR/services/matching-engine/go.mod); mkdir -p /tmp/gocache /tmp/gomod; docker run --rm --network host -v \$PWD/$REMOTE_DIR/services/matching-engine:/src -v /tmp/gocache:/root/.cache/go-build -v /tmp/gomod:/go/pkg/mod -w /src -e REEF_KAFKA_INTEGRATION_BOOTSTRAP=127.0.0.1:19092 -e KAFKA_DRAIN=1 -e KAFKA_DRAIN_PAIRS=$pairs -e KAFKA_DRAIN_BATCHES=$batches $(for e in "$@"; do printf -- '-e %s ' "$e"; done) golang:\$GOV go test ./internal/streamdirect -run TestKafkaLaneDrainRate -v -count=1 -timeout 40m > /tmp/drain-$label.txt 2>&1; grep -E 'LANE-DRAIN|^ok' /tmp/drain-$label.txt; if ! grep -q 'LANE-DRAIN batch' /tmp/drain-$label.txt; then echo 'DRAIN-FAILED, test output follows'; tail -25 /tmp/drain-$label.txt; docker logs --tail 15 reef-it-rp 2>&1; fi; docker rm -f -v reef-it-rp >/dev/null 2>&1; docker ps -a --format 'LEFTOVER {{.Names}}'" | sed "s/^/$label: /" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
}

run_engine_cpu() {
  # The engine lane's CPU per crossing pair without a broker, on this host's cores (compare with the laptop's 27 us).
  log "case engine-cpu"
  remote "GOV=\$(awk '/^go /{print \$2}' $REMOTE_DIR/services/matching-engine/go.mod); mkdir -p /tmp/gocache /tmp/gomod; docker run --rm -v \$PWD/$REMOTE_DIR/services/matching-engine:/src -v /tmp/gocache:/root/.cache/go-build -v /tmp/gomod:/go/pkg/mod -w /src golang:\$GOV go test ./internal/streamdirect -run xxx -bench LaneProcessOnce -benchtime 15x 2>&1 | grep -E 'Benchmark|FAIL|ok'" | sed 's/^/engine-cpu: /' | tee -a "$OUT_DIR/matrix.txt" || log "case engine-cpu failed"
}

run_cap() {
  # run_cap LABEL ENV...  -- CapacityLaneThroughputTest (real Streams lanes, per-lane projection into Postgres, resolved-context source) with broker and Postgres data on tmpfs.
  local label="$1"; shift
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  log "case $label ($*)"
  remote "cd $REMOTE_DIR/services/platform-runtime && env JAVA_TOOL_OPTIONS=-XX:+UseParallelGC S4_PG_DOCKER=--tmpfs=/var/lib/postgresql/data:rw,size=8g,mode=1777 S4_RP_DOCKER=--tmpfs=/var/lib/redpanda/data:rw,size=16g,uid=101,gid=101,mode=0777 CAP_INSTRUMENTS=64 CAP_ACCOUNTS=200 CAP_SOURCE=resolved $* timeout 1500 ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*CapacityLaneThroughputTest*' -i --no-daemon 2>&1 | grep -E 'CAPACITY-(CPU|RATE|LANES)|FAILED|AssertionFailed|BUILD' ; docker ps -a --format 'LEFTOVER {{.Names}}'" | sed "s/^/$label: /" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
}

HOST_MEM_GB=""
run_fullchain() {
  # run_fullchain LABEL RATE SECONDS [ENV...]  -- scripts/dev/calcify-fullchain-soak.mjs on the host; a fresh stack per case (reference data and funding are per run id)
  local label="$1" rate="$2" seconds="$3"; shift 3
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  local id="fc${label//[^A-Za-z0-9]/}"
  local timeout_s=$(( seconds + ${REEF_FC_DRAIN_SECONDS:-600} + 1500 ))
  log "case $label (${rate} trades/s for ${seconds}s, id $id: $*)"
  remote "cd $REMOTE_DIR && (test -f .env || cp .env.example .env) && rm -rf /tmp/fcout/$label && mkdir -p /tmp/fcout/$label && env FC_ID=$id FC_RATE=$rate FC_SECONDS=$seconds FC_OUT_DIR=/tmp/fcout/$label FC_TMPFS=true FC_MODE=${REEF_FC_MODE:-capture-settle-v1} FC_EVICT=${REEF_FC_EVICT:-true} FC_QUANTITY=${REEF_FC_QUANTITY:-1} FC_TMPFS_BOUNDARY_GB=${REEF_FC_TMPFS_BOUNDARY_GB:-40} FC_TMPFS_BROKER_GB=${REEF_FC_TMPFS_BROKER_GB:-24} $* timeout $timeout_s node scripts/dev/calcify-fullchain-soak.mjs 2>&1 | tee /tmp/fcout/$label/run.log | grep -E 'FULLCHAIN-RESULT|FAILED|fullchain .* t=' | tail -n 200; echo \"leftover containers: \$(docker ps -aq | wc -l)\"" | sed "s/^/$label: /" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
  mkdir -p "$OUT_DIR/fullchain"
  remote "tar -C /tmp/fcout -czf - $label" | tar -C "$OUT_DIR/fullchain" -xzf - || log "could not fetch the full-chain output of $label"
  log "fetched $OUT_DIR/fullchain/$label: $(ls "$OUT_DIR/fullchain/$label" 2>/dev/null | tr '\n' ' ')"
}

run_soak() {
  # run_soak LABEL STORE SECONDS RP_GB PG_GB HEAP_GB RATE [ENV...]  -- CapacitySoakTest (docs/work/CALCIFY_SOAK_HARNESS.md): a paced soak in capture-settle-v1 mode with the resolved-context source.
  #   STORE tmpfs: broker and Postgres data on tmpfs (RP_GB / PG_GB are the tmpfs size limits, not allocations; a full tmpfs fails the run loudly).
  #   STORE disk:  broker on tmpfs, Postgres on the root disk (or REEF_SOAK_PG_STORE=volume: the attached volume at /mnt/pgvol) with synchronous_commit=off, PG_GB unused.
  # The CSV time series and the full gradle log are fetched into $OUT_DIR/soak/LABEL right after the case, so a later failure cannot lose them.
  local label="$1" store="$2" seconds="$3" rp_gb="$4" pg_gb="$5" heap_gb="$6" rate="$7"; shift 7
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  [ -n "$HOST_MEM_GB" ] || HOST_MEM_GB="$(remote "free -g | awk '/^Mem:/{print \$2}'")"
  local need=$(( rp_gb + heap_gb + 4 )); [ "$store" = tmpfs ] && need=$(( need + pg_gb ))
  if [ "$need" -gt "$HOST_MEM_GB" ] && [ "${REEF_SOAK_FORCE:-0}" != 1 ]; then
    log "SKIP $label: tmpfs stores + heap + 4 GB need $need GB, the host has $HOST_MEM_GB GB (REEF_SOAK_FORCE=1 runs it anyway; lower REEF_SOAK_*_GB or use a bigger REEF_DO_SIZE)" | tee -a "$OUT_DIR/matrix.txt"
    return 0
  fi
  local pgdocker pgsync
  if [ "$store" = tmpfs ]; then pgdocker="S4_PG_DOCKER=--tmpfs=/var/lib/postgresql/data:rw,size=${pg_gb}g,mode=1777"; pgsync=false
  elif [ "${REEF_SOAK_PG_STORE:-root}" = volume ]; then pgdocker="S4_PG_DOCKER=--volume=/mnt/pgvol/pg:/var/lib/postgresql/data"; pgsync=true
  else pgdocker=""; pgsync=true; fi
  # a case-specific override (CAP_PG_SYNC_OFF=... in the trailing ENV) wins because env takes the last assignment
  local timeout_s=$(( seconds + ${CAP_SOAK_DRAIN_SECONDS:-600} + 900 ))
  log "case $label (store $store, ${seconds}s at $rate/s, rp ${rp_gb}g pg ${pg_gb}g heap ${heap_gb}g: $*)"
  remote "sudo rm -rf /mnt/pgvol/pg/* 2>/dev/null; rm -rf /tmp/soakout/$label; mkdir -p /tmp/soakout/$label; echo \"ROOTDISK-BEFORE \$(df -h / | tail -1)\"; cd $REMOTE_DIR/services/platform-runtime && env JAVA_TOOL_OPTIONS=-XX:+UseParallelGC S4_RP_DOCKER=--tmpfs=/var/lib/redpanda/data:rw,size=${rp_gb}g,uid=101,gid=101,mode=0777 $pgdocker CAP_PG_SYNC_OFF=$pgsync CAP_INSTRUMENTS=64 CAP_ACCOUNTS=200 CAP_SOURCE=resolved CAP_DECISION_MODE=capture-settle-v1 CAP_LANES=${REEF_SOAK_LANES:-1} CAP_HOT_SHARE=${REEF_SOAK_HOT_SHARE:-0} CAP_SOAK_ABORT_ON_LAG=${REEF_SOAK_ABORT_ON_LAG:-true} CAP_SOAK_SECONDS=$seconds CAP_SOAK_RATE=$rate CAP_SOAK_OUT_DIR=/tmp/soakout/$label CAP_SOAK_LABEL=$label ${REEF_SOAK_EXTRA_ENV:-} $* timeout $timeout_s ./gradlew cleanTest test -PbrokerTests -PresearchTests -PtestHeap=${heap_gb}g --tests '*CapacitySoakTest*' -i --no-daemon 2>&1 | tee /tmp/soakout/$label/gradle.log | grep -E 'CAPACITY-SOAK-(CONFIG|CSV|POSTGRES|BROKER|BASELINE|SAMPLE|PRODUCER-DONE|RESULT|FAIL|WARN)|FAILED|AssertionFailed|OutOfMemory|BUILD'; echo \"ROOTDISK-AFTER \$(df -h / | tail -1)\"; docker ps -a --format 'LEFTOVER {{.Names}}'; docker volume ls -q | sed 's/^/LEFTOVER-VOLUME /'" | sed "s/^/$label: /" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
  mkdir -p "$OUT_DIR/soak"
  remote "tar -C /tmp/soakout -czf - $label" | tar -C "$OUT_DIR/soak" -xzf - || log "could not fetch the soak output of $label"
  log "fetched $OUT_DIR/soak/$label: $(ls "$OUT_DIR/soak/$label" 2>/dev/null | tr '\n' ' ')"
}

: > "$OUT_DIR/matrix.txt"
case "$MATRIX" in
  lane)
    # CAL-FIN-P23: what one quiet, dedicated host says about the engine lane and the finance lane. Three interleaved sweeps of the engine drain (the laptop's three agreed within 2 percent),
    # one sweep with compression off, then one hot finance lane with its projector (default and 8,000 record chunks, twice each), then four uniform lanes for the aggregate.
    run_engine_cpu
    for r in 1 2 3; do run_engine_drain "engine-drain-$r" 200000 500,1000,2000,4000; done
    run_engine_drain "engine-drain-nocompress" 200000 500,2000 MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE=none
    for r in 1 2; do
      run_cap "cap-hot-lane-$r" CAP_LANES=1 CAP_TRADES=600000
      run_cap "cap-hot-lane-apply8000-$r" CAP_LANES=1 CAP_TRADES=600000 CAP_MAX_APPLY=8000
    done
    run_cap "cap-4lanes" CAP_LANES=4 CAP_TRADES=1200000
    ;;
  soak)
    # Paced soak (docs/work/CALCIFY_SOAK_HARNESS.md): capture-settle-v1, one lane, REEF_SOAK_RATE trades/s (10000) held for 15 and 30 minutes.
    #   tmpfs15 / tmpfs30: broker AND Postgres on tmpfs.  disk15 / disk30: broker on tmpfs, Postgres on the root disk with synchronous_commit=off (REEF_SOAK_PG_STORE=volume: the attached volume).
    #   REEF_SOAK_CASES picks and orders the cases (default: tmpfs15 disk15 tmpfs30 disk30).
    # Sizes are LIMITS (tmpfs size=, JVM -Xmx), planned from the laptop's 450,000-trade run: broker ~2.2 KB and Postgres ~1.5 KB per trade (plus about 1 GB of WAL), JVM heap used ~1.2 KB per trade
    # (the ledger keeps every execution of the run in memory). 15 minutes is 9 million trades; 30 minutes 18 million. The defaults of the 15-minute cases (20 + 16 + 22 GB + 4) fit a 64 GB host;
    # the 30-minute cases (40 + 30 + 40 GB + 4) need roughly 114 GB and are skipped (with the numbers) on a host that cannot hold them unless REEF_SOAK_FORCE=1.
    SOAK_RATE="${REEF_SOAK_RATE:-10000}"; SHORT="${REEF_SOAK_SHORT_SECONDS:-900}"; LONG="${REEF_SOAK_LONG_SECONDS:-1800}"
    S_RP="${REEF_SOAK_RP_TMPFS_GB:-20}"; S_PG="${REEF_SOAK_PG_TMPFS_GB:-16}"; S_HEAP="${REEF_SOAK_HEAP_GB:-22}"
    L_RP="${REEF_SOAK_RP_TMPFS_GB:-40}"; L_PG="${REEF_SOAK_PG_TMPFS_GB:-30}"; L_HEAP="${REEF_SOAK_HEAP_GB:-40}"
    for c in ${REEF_SOAK_CASES:-tmpfs15 disk15 tmpfs30 disk30}; do
      case "$c" in
        tmpfs15) run_soak "soak-tmpfs-$((SHORT / 60))m" tmpfs "$SHORT" "$S_RP" "$S_PG" "$S_HEAP" "$SOAK_RATE" ;;
        disk15) run_soak "soak-pgdisk-$((SHORT / 60))m" disk "$SHORT" "$S_RP" 0 "$S_HEAP" "$SOAK_RATE" ;;
        tmpfs30) run_soak "soak-tmpfs-$((LONG / 60))m" tmpfs "$LONG" "$L_RP" "$L_PG" "$L_HEAP" "$SOAK_RATE" ;;
        disk30) run_soak "soak-pgdisk-$((LONG / 60))m" disk "$LONG" "$L_RP" 0 "$L_HEAP" "$SOAK_RATE" ;;
        *) echo "unknown REEF_SOAK_CASES entry: $c (tmpfs15 tmpfs30 disk15 disk30)" >&2; exit 1 ;;
      esac
    done
    ;;
  soaksmoke)
    # 60 s of each store layout at a modest rate, small tmpfs sizes and heap: proves the harness, the fetch and the cleanup on a small droplet before a real soak is rented.
    SMOKE="${REEF_SOAK_SMOKE_SECONDS:-60}"; SMOKE_RATE="${REEF_SOAK_SMOKE_RATE:-3000}"
    run_soak "soaksmoke-tmpfs" tmpfs "$SMOKE" "${REEF_SOAK_SMOKE_RP_GB:-3}" "${REEF_SOAK_SMOKE_PG_GB:-3}" "${REEF_SOAK_SMOKE_HEAP_GB:-4}" "$SMOKE_RATE"
    run_soak "soaksmoke-pgdisk" disk "$SMOKE" "${REEF_SOAK_SMOKE_RP_GB:-3}" 0 "${REEF_SOAK_SMOKE_HEAP_GB:-4}" "$SMOKE_RATE"
    ;;
  fullchain)
    remote "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs >/dev/null && node --version" | tee -a "$OUT_DIR/matrix.txt"
    # a 20 s case first: a setup fault (image build, tmpfs, nodejs, the load tool) shows here once instead of four times
    REEF_FC_TMPFS_BOUNDARY_GB=4 REEF_FC_TMPFS_BROKER_GB=3 run_fullchain "smoke500" 500 20
    if ! grep -q '"integrity": "ok"' "$OUT_DIR/fullchain/smoke500/result.json" 2>/dev/null; then log "the smoke case did not pass; not starting the ladder"; exit 1; fi
    for rate in ${REEF_FC_RATES:-2500 5000 7500 10000}; do run_fullchain "r$rate" "$rate" "${REEF_FC_SECONDS:-60}"; done
    if [ -n "${REEF_FC_LONG_RATE:-}" ]; then run_fullchain "hold${REEF_FC_LONG_RATE}" "$REEF_FC_LONG_RATE" "${REEF_FC_LONG_SECONDS:-900}"; fi
    ;;
  fullchainsmoke)
    remote "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs >/dev/null && node --version" | tee -a "$OUT_DIR/matrix.txt"
    REEF_FC_TMPFS_BOUNDARY_GB=4 REEF_FC_TMPFS_BROKER_GB=3 run_fullchain "smoke500" 500 20
    ;;
  enginesmoke)
    run_engine_drain "engine-smoke" 5000 500,2000
    ;;
  *)
    echo "unknown matrix: $MATRIX (lane | enginesmoke | soak | soaksmoke | fullchain | fullchainsmoke)" >&2; exit 1
    ;;
esac

log "done in $(elapsed)s; the droplet is destroyed on exit"

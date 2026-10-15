#!/usr/bin/env bash
# One time-boxed run of the finance chain spike (S4EndToEndSpikeTest) on a quiet dedicated DigitalOcean droplet, owner-approved 2026-10-14.
# It provisions the existing do-benchmark stack, ships ONLY tracked files (git archive of HEAD: never .env, .planning, .mcp.json or other untracked files),
# installs a JDK on the host, runs a short matrix, fetches the outputs, and destroys the droplet on every exit path.
#
#   REEF_DO_SIZE=c-16 scripts/dev/calcify-financial/droplet-chain-run.sh [matrix]    (matrix: default | compare | tmpfs | baseline | smoke | s1smoke)
#   `lane` (CAL-FIN-P23) measures the engine lane's drain rate by batch size against a tmpfs Redpanda, then one finance lane with its projector on tmpfs stores; `capmode` runs the finance cases in both decision modes; `capmini` is only the finance cases (one hot lane twice, four lanes); `lanesmoke` is the same at a few seconds each and `enginesmoke` is only its engine case.
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

run_engine_drain() {
  # run_engine_drain LABEL PAIRS BATCHES [ENV...]  -- the Go lane drain test (real source, real transactional publisher) against a Redpanda whose data directory is tmpfs.
  # The Go toolchain runs in the golang image matching go.mod so the host needs no Go; the event topic's max.message.bytes and the producer limit are raised by the test itself.
  # Redpanda is sized from the host (half the cores up to 4, a quarter of the memory up to 8 GB) so the same case runs on a small smoke droplet and on the measurement host.
  local label="$1" pairs="$2" batches="$3"; shift 3
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  log "case $label (pairs $pairs batches $batches $*)"
  remote "docker rm -f -v reef-it-rp >/dev/null 2>&1; NP=\$(nproc); SMP=\$(( NP / 2 )); [ \$SMP -gt 4 ] && SMP=4; [ \$SMP -lt 1 ] && SMP=1; MEMG=\$(( \$(free -g | awk '/^Mem:/{print \$2}') / 4 )); [ \$MEMG -gt 8 ] && MEMG=8; [ \$MEMG -lt 1 ] && MEMG=1; docker run -d --rm --name reef-it-rp --network host --tmpfs /var/lib/redpanda/data:rw,size=\${MEMG}g,uid=101,gid=101,mode=0777 redpandadata/redpanda:v26.2.3 redpanda start --mode dev-container --smp \$SMP --memory \${MEMG}G --reserve-memory 0M --overprovisioned --node-id 0 --check=false --kafka-addr 0.0.0.0:19092 --advertise-kafka-addr 127.0.0.1:19092 >/dev/null && echo \"redpanda smp=\$SMP memory=\${MEMG}G\"; ready=0; for i in \$(seq 1 60); do docker exec reef-it-rp rpk cluster info >/dev/null 2>&1 && ready=1 && break; sleep 2; done; [ \$ready = 1 ] || { echo 'REDPANDA-NOT-READY'; docker logs --tail 15 reef-it-rp 2>&1; }; GOV=\$(awk '/^go /{print \$2}' $REMOTE_DIR/services/matching-engine/go.mod); mkdir -p /tmp/gocache /tmp/gomod; docker run --rm --network host -v \$PWD/$REMOTE_DIR/services/matching-engine:/src -v /tmp/gocache:/root/.cache/go-build -v /tmp/gomod:/go/pkg/mod -w /src -e REEF_KAFKA_INTEGRATION_BOOTSTRAP=127.0.0.1:19092 -e KAFKA_DRAIN=1 -e KAFKA_DRAIN_PAIRS=$pairs -e KAFKA_DRAIN_BATCHES=$batches $(for e in "$@"; do printf -- '-e %s ' "$e"; done) golang:\$GOV go test ./internal/streamdirect -run TestKafkaLaneDrainRate -v -count=1 -timeout 40m > /tmp/drain-$label.txt 2>&1; grep -E 'LANE-DRAIN|^ok' /tmp/drain-$label.txt; if ! grep -q 'LANE-DRAIN batch' /tmp/drain-$label.txt; then echo 'DRAIN-FAILED, test output follows'; tail -25 /tmp/drain-$label.txt; docker logs --tail 15 reef-it-rp 2>&1; fi; docker rm -f -v reef-it-rp >/dev/null 2>&1; docker ps -a --format 'LEFTOVER {{.Names}}'" | sed "s/^/$label: /" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
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
  capmode)
    # CAL-FIN-P25: the same finance cases in both decision modes on one host (hot lane twice per mode, then the uniform lanes once per mode unless REEF_CAP_SKIP_4LANES=1).
    for mode in two-step capture-settle-v1; do
      run_cap "cap-hot-$mode-1" CAP_LANES=1 CAP_TRADES=600000 CAP_DECISION_MODE=$mode
      run_cap "cap-hot-$mode-2" CAP_LANES=1 CAP_TRADES=600000 CAP_DECISION_MODE=$mode
    done
    if [ "${REEF_CAP_SKIP_4LANES:-0}" != 1 ]; then
      for mode in two-step capture-settle-v1; do run_cap "cap-4lanes-$mode" CAP_LANES=4 CAP_TRADES=${REEF_CAP_4LANE_TRADES:-1200000} CAP_DECISION_MODE=$mode; done
    fi
    ;;
  capmini)
    # The finance lane only (CAL-FIN-P25): one hot lane twice and four uniform lanes, to compare hosts and code versions cheaply (about 20 minutes after bootstrap).
    run_cap "cap-hot-lane-1" CAP_LANES=1 CAP_TRADES=600000
    run_cap "cap-hot-lane-2" CAP_LANES=1 CAP_TRADES=600000
    run_cap "cap-4lanes" CAP_LANES=4 CAP_TRADES=1200000
    ;;
  enginesmoke)
    run_engine_drain "engine-smoke" 5000 500,2000
    ;;
  lanesmoke)
    run_engine_drain "engine-smoke" 5000 500,2000
    run_cap "cap-smoke" CAP_LANES=1 CAP_TRADES=20000
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

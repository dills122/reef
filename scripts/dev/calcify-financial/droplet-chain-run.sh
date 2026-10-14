#!/usr/bin/env bash
# One time-boxed run of the finance chain spike (S4EndToEndSpikeTest) on a quiet dedicated DigitalOcean droplet, owner-approved 2026-10-14.
# It provisions the existing do-benchmark stack, ships ONLY tracked files (git archive of HEAD: never .env, .planning, .mcp.json or other untracked files),
# installs a JDK on the host, runs a short matrix, fetches the outputs, and destroys the droplet on every exit path.
#
#   REEF_DO_SIZE=c-16 scripts/dev/calcify-financial/droplet-chain-run.sh [matrix]    (matrix: default | smoke)
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
"$HARNESS" up
HOST="$(outputs public_ipv4)"; USER_NAME="$(outputs ssh_user)"
KEY="${REEF_DO_SSH_PRIVATE_KEY:-${REEF_DO_SSH_PUBLIC_KEY:-$HOME/.ssh/id_ed25519.pub}}"; KEY="${KEY%.pub}"
log "host $USER_NAME@$HOST"

log "shipping tracked files only (git archive HEAD)"
git -C "$ROOT_DIR" archive --format=tar HEAD | remote "mkdir -p $REMOTE_DIR && tar -x -C $REMOTE_DIR"

log "installing a JDK and recording the host"
remote "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk-headless >/dev/null && java -version 2>&1 | head -1 && nproc && lscpu | grep -E 'Model name|^CPU\\(s\\)' && free -g | head -2 && docker --version && uptime" | tee "$OUT_DIR/host.txt"

run_case() {
  # run_case LABEL ENV...   -- each case runs the S4 test once with 100,000 trades per lane and keeps its output
  local label="$1"; shift
  if [ "$(elapsed)" -gt "$DEADLINE_SECONDS" ]; then log "deadline reached before $label; stopping the matrix"; return 1; fi
  log "case $label ($*)"
  remote "cd $REMOTE_DIR/services/platform-runtime && env $* S0_OUT_DIR=/tmp/s4out S4_TRADES=\${S4_TRADES:-100000} S4_ACCOUNTS=1000 S4_PG=nobindingfk timeout 1500 ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*S4EndToEndSpikeTest' -i --no-daemon 2>&1 | grep -E '^ +S4 |lanes together|projectors together|CPU per decision|  gc |  lane |S4-|FAILED|AssertionFailed|BUILD' ; docker ps -a --format 'LEFTOVER {{.Names}}'" | tee -a "$OUT_DIR/matrix.txt" || log "case $label failed"
}

: > "$OUT_DIR/matrix.txt"
case "$MATRIX" in
  smoke) run_case "smoke-1lane" S4_LANES=1 S4_TRADES=5000 ;;
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
    ;;
esac

log "fetching the metrics file"
remote "cat /tmp/s4out/s0-metrics.properties 2>/dev/null; cat /tmp/s4out/s4-end-to-end.txt 2>/dev/null" > "$OUT_DIR/metrics-and-last-log.txt" || true
log "done in $(elapsed)s; the droplet is destroyed on exit"

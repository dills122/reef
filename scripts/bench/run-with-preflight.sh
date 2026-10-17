#!/usr/bin/env bash
# Run a benchmark command only after scripts/bench/preflight.mjs passed, and stamp the result next to its output.
#   scripts/bench/run-with-preflight.sh <class a|b|c> <launcher materializer|nodb|none> -- <command...>
# BENCH_PREFLIGHT=skip: documented escape hatch. Prints a loud banner before AND after the run and stamps
# 'PREFLIGHT SKIPPED' (preflight.json + PREFLIGHT_SKIPPED.txt) so the numbers are never mistaken for valid.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cls="${1:?class a|b|c required}"
launcher="${2:?launcher materializer|nodb|none required}"
shift 2
[ "${1:-}" = "--" ] && shift
[ "$#" -gt 0 ] || { echo "usage: $0 <class> <launcher> -- <command...>" >&2; exit 2; }

cd "$ROOT_DIR"
stamp_ts="$(date +%Y%m%dT%H%M%S)"
case "$cls" in
  b)
    # The soak derives its output directory from FC_ID; fix both here so the stamp lands next to the run.
    export FC_ID="${FC_ID:-fc$(node -e 'process.stdout.write(String(Date.now()))')}"
    export FC_OUT_DIR="${FC_OUT_DIR:-reports/calcify-fullchain/$FC_ID}"
    stamp_dir="$FC_OUT_DIR"
    ;;
  c)
    if [ "$launcher" = "materializer" ]; then
      stamp_dir="${DEV_STRESS_ARTIFACT_DIR:-/tmp/reef-venue-event-materializer-stress}"
    else
      stamp_dir="${DEV_STRESS_ARTIFACT_DIR:-reports/bench-preflight/c-$launcher-$stamp_ts}"
    fi
    ;;
  *) stamp_dir="${BENCH_STAMP_DIR:-reports/bench-preflight/$cls-$stamp_ts}" ;;
esac

rc=0
node scripts/bench/preflight.mjs --class "$cls" --launcher "$launcher" --stamp-dir "$stamp_dir" || rc=$?
if [ "$rc" -ne 0 ]; then
  echo "preflight did not pass (exit $rc): the benchmark was NOT started. Fix the configuration above, or (numbers become INVALID) BENCH_PREFLIGHT=skip." >&2
  exit "$rc"
fi

cmd_rc=0
"$@" || cmd_rc=$?

if [ "${BENCH_PREFLIGHT:-}" = "skip" ]; then
  echo "" >&2
  echo "################ PREFLIGHT SKIPPED: the numbers above are INVALID (stamp: $stamp_dir/preflight.json) ################" >&2
fi
exit "$cmd_rc"

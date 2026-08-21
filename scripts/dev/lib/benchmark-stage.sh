#!/usr/bin/env bash

benchmark_run_stage() {
  local log_dir="$1"
  local log_tail="$2"
  local name="$3"
  shift 3

  local log_file="$log_dir/stage-${name}.log"
  local started_at
  started_at="$(date +%s)"
  echo "[$(benchmark_timestamp)] stage: $name (log=$log_file)"

  local status
  if "$@" >"$log_file" 2>&1 </dev/null; then
    status=0
  else
    status=$?
  fi

  local finished_at
  finished_at="$(date +%s)"
  if [ "$status" -eq 0 ]; then
    echo "[$(benchmark_timestamp)] stage complete: $name duration_seconds=$((finished_at - started_at))"
    return 0
  fi

  echo "[$(benchmark_timestamp)] stage failed: $name status=$status duration_seconds=$((finished_at - started_at)) log=$log_file" >&2
  echo "[$(benchmark_timestamp)] tail: $name last $log_tail lines" >&2
  tail -n "$log_tail" "$log_file" >&2 || true
  return "$status"
}

benchmark_timestamp() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

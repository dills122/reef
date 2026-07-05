#!/usr/bin/env bash
set -euo pipefail

BASE="${REEF_DEPLOY_DIR:-/opt/reef}"
cd "$BASE"

LOCK="/tmp/reef-simulator-runner.lock"

flock -n "$LOCK" docker compose --profile manual run --rm simulator "$@"


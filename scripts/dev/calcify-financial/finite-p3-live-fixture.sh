#!/usr/bin/env bash
# Disposable Postgres fixture for the finite P3 financial-path live recording.
#   finite-p3-live-fixture.sh up     start a throwaway postgres:16-alpine, apply the boundary migrations, create the projector and reader roles
#   finite-p3-live-fixture.sh env    print the path of the env file to `source` (the test harness reads CALCIFY_FINITE_POSTGRES_TEST_*)
#   finite-p3-live-fixture.sh down   remove the container WITH its volume and delete the private credential directory
# Credentials are random, live only in files under a mode-700 directory, and reach psql over stdin: never in arguments, never in the environment of other processes.
set -euo pipefail

NAME="${CALCIFY_LIVE_PG_NAME:-reef-finite-p3-live-pg}"
PORT="${CALCIFY_LIVE_PG_PORT:-55433}"
DIR="${CALCIFY_LIVE_DIR:-${TMPDIR:-/tmp}/reef-finite-p3-live}"
IMAGE="${CALCIFY_LIVE_PG_IMAGE:-postgres:16-alpine}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

die() { echo "finite-p3-live-fixture: $*" >&2; exit 1; }

up() {
  command -v docker >/dev/null || die "docker is required"
  docker info >/dev/null 2>&1 || die "the docker daemon is not running"
  [ -z "$(docker ps -aq --filter "name=^${NAME}$")" ] || die "container $NAME already exists; run '$0 down' first"
  ( umask 077; mkdir -p "$DIR"; chmod 700 "$DIR"
    openssl rand -hex 16 > "$DIR/projector.pw"; openssl rand -hex 16 > "$DIR/reader.pw"; openssl rand -hex 16 > "$DIR/admin.pw" )
  docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD="$(cat "$DIR/admin.pw")" -e POSTGRES_DB=reef -p "127.0.0.1:${PORT}:5432" "$IMAGE" >/dev/null
  for _ in $(seq 1 60); do docker exec "$NAME" psql -U postgres -d reef -c 'select 1' >/dev/null 2>&1 && break; sleep 1; done
  docker exec "$NAME" psql -U postgres -d reef -c 'select 1' >/dev/null 2>&1 || die "postgres did not become ready"
  {
    echo "CREATE SCHEMA IF NOT EXISTS boundary;"
    for f in "$ROOT"/scripts/dev/db/migrations/boundary/*.sql; do cat "$f"; echo; done
    printf "CREATE ROLE fin_projector LOGIN PASSWORD '%s';\n" "$(cat "$DIR/projector.pw")"
    printf "CREATE ROLE fin_reader LOGIN PASSWORD '%s';\n" "$(cat "$DIR/reader.pw")"
    cat <<'SQL'
GRANT USAGE ON SCHEMA boundary TO fin_projector, fin_reader;
GRANT SELECT ON boundary.calcify_finance_checkpoint, boundary.calcify_finance_decision, boundary.calcify_finance_position, boundary.calcify_finance_obligation, boundary.calcify_finance_attempt TO fin_reader;
GRANT SELECT, INSERT, UPDATE ON boundary.calcify_finance_checkpoint, boundary.calcify_finance_decision, boundary.calcify_finance_position, boundary.calcify_finance_obligation, boundary.calcify_finance_attempt TO fin_projector;
SQL
  } | docker exec -i -e PGOPTIONS="-c client_min_messages=warning" "$NAME" psql -q -v ON_ERROR_STOP=1 -U postgres -d reef >/dev/null
  ( umask 077; cat > "$DIR/env.sh" <<ENV
export CALCIFY_FINITE_POSTGRES_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:${PORT}/reef
export CALCIFY_FINITE_POSTGRES_TEST_PROJECTOR_USER=fin_projector
export CALCIFY_FINITE_POSTGRES_TEST_PROJECTOR_PASSWORD_FILE=${DIR}/projector.pw
export CALCIFY_FINITE_POSTGRES_TEST_READER_USER=fin_reader
export CALCIFY_FINITE_POSTGRES_TEST_READER_PASSWORD_FILE=${DIR}/reader.pw
ENV
  )
  echo "ready: container $NAME on 127.0.0.1:${PORT}; source $DIR/env.sh"
}

down() {
  docker rm -f -v "$NAME" >/dev/null 2>&1 || true
  rm -rf "$DIR"
  echo "removed: container $NAME (with its volume) and $DIR"
}

case "${1:-}" in
  up) up ;;
  env) echo "$DIR/env.sh" ;;
  down) down ;;
  *) die "usage: $0 up|env|down" ;;
esac

#!/usr/bin/env bash
set -euo pipefail

# POSTGRES_DB/POSTGRES_USER for this container are the superuser bootstrap
# identity (set via secrets/postgres-admin.env), already created by the
# official postgres image entrypoint. This just adds the app-facing role.
# Schema creation and schema-level grants happen in apply-migrations.sh
# (invoked with REEF_MIGRATION_DOMAINS=admin REEF_APP_USER=admin_app
# REEF_POSTGRES_SERVICE=postgres-admin REEF_POSTGRES_DB=admin), same as the
# reef_app pattern for the main reef DB. This just creates the role.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE USER admin_app WITH PASSWORD '${ADMIN_APP_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON DATABASE ${POSTGRES_DB} TO admin_app;
SQL

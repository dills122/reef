#!/usr/bin/env bash
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<SQL
CREATE DATABASE openbao;
CREATE DATABASE reef;

CREATE USER openbao WITH PASSWORD '${OPENBAO_DB_PASSWORD}';
CREATE USER reef_app WITH PASSWORD '${REEF_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON DATABASE openbao TO openbao;
GRANT ALL PRIVILEGES ON DATABASE reef TO reef_app;

\connect openbao
GRANT USAGE, CREATE ON SCHEMA public TO openbao;

\connect reef
GRANT USAGE, CREATE ON SCHEMA public TO reef_app;
SQL


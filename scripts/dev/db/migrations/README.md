# Dev DB Migrations

Forward-only migration convention for local development.

Domain folders:
- `runtime/`
- `auth/`
- `admin/`
- `boundary/`
- `command_log/`
- `orchestration/`
- `settlement/`
- `analytics/` (planned)

Current implementation note:
- runtime, boundary, and auth service initialization currently creates schema-qualified domain tables for local compatibility
- those service-side bootstrap paths are transitional compatibility bridges, not the target ownership model
- new durable schema work should land in these migration folders and should not add new root-level or search-path-dependent bootstrap tables
- `make dev-up`, `make dev-reset`, and `make dev-db-migrate` apply migrations through Docker Compose Postgres and record checksums in `public.reef_schema_migrations`
- clean-stack migration apply and live schema-placement tests are available; before removing service-side bootstrap, add CI coverage for the migration execution order

Rules:
- place SQL files in the owning domain folder only
- use monotonic prefixes (`0001_`, `0002_`, ...)
- do not edit existing applied migrations; add a new migration instead
- avoid cross-domain foreign keys
- use `$(JS_RUNTIME) scripts/dev/db/migrate.mjs --dry-run` to validate migration order and checksums without touching Docker

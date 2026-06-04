# Dev DB Migrations

Forward-only migration convention for local development.

Domain folders:
- `runtime/`
- `auth/`
- `admin/`
- `boundary/`
- `orchestration/`
- `analytics/` (planned)

Current implementation note:
- runtime, boundary, and auth service initialization currently creates schema-qualified domain tables for local compatibility
- those service-side bootstrap paths are transitional compatibility bridges, not the target ownership model
- new durable schema work should land in these migration folders and should not add new root-level or search-path-dependent bootstrap tables
- `make dev-db-migrate` applies migrations through Docker Compose Postgres and records checksums in `public.reef_schema_migrations`
- clean-stack migration apply and live schema-placement tests are available; before removing service-side bootstrap, decide whether migrations are explicit or automatic during startup

Rules:
- place SQL files in the owning domain folder only
- use monotonic prefixes (`0001_`, `0002_`, ...)
- do not edit existing applied migrations; add a new migration instead
- avoid cross-domain foreign keys
- use `$(JS_RUNTIME) scripts/dev/db/migrate.mjs --dry-run` to validate migration order and checksums without touching Docker

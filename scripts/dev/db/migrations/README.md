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
- some durable tables are still created by runtime/boundary service initialization for local compatibility
- new durable schema work should prefer these migration folders and domain schemas instead of adding more root-level bootstrap tables

Rules:
- place SQL files in the owning domain folder only
- use monotonic prefixes (`0001_`, `0002_`, ...)
- do not edit existing applied migrations; add a new migration instead
- avoid cross-domain foreign keys

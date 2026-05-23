# DB Split-Readiness Guardrails

This document defines constraints for the single-Postgres local model so future scoped DB extraction remains low-friction.

## Current domain schemas

- `runtime`
- `auth`
- `admin`
- `boundary`

## Guardrails

1. No cross-domain foreign keys
- foreign keys must remain inside a single domain schema
- cross-domain references should use identifiers and application-level validation

2. No cross-domain repository coupling
- repositories/services should not query another domain schema directly
- cross-domain interactions happen through application-layer commands/events

3. Forward-only migrations by domain
- each domain owns its own migration folder under `scripts/dev/db/migrations/`
- no editing of applied migration files

4. Boundary persistence isolation
- API idempotency and boundary-specific persistence remain in `boundary` schema
- runtime write-model persistence remains in `runtime` schema

## Current local bootstrap model

- schema creation: `scripts/dev/db/init/001_create_domain_schemas.sql`
- runtime/admin table bootstrap: runtime persistence initialization
- boundary idempotency table bootstrap: boundary idempotency persistence initialization

This is acceptable for current phase, with the migration folders now established for forward-only evolution.

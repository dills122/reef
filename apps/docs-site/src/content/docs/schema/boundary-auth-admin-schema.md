---
title: Boundary, Auth & Admin Schema
description: API idempotency, roles/actor-roles, and policy/audit tables.
---

## Boundary Schema

**`boundary.idempotency_records`** — `client_id text`, `route text`, `idempotency_key text`, `request_hash text`, `response_status int`, `response_body jsonb`, `created_at timestamptz`, `expires_at timestamptz`; primary key `(client_id, route, idempotency_key)`. Backs the `Idempotency-Key` contract on every `/api/v1` mutation — see [API Overview](/api/overview/).

## Auth Schema

**`auth.roles`** — `role_id text pk`, `permissions jsonb`, `created_at timestamptz`.

**`auth.actor_roles`** — `actor_id text`, `role_id text`, `created_at timestamptz`; primary key `(actor_id, role_id)`.

## Admin Schema

**`admin.market_calendar_profiles`** — `profile_id text pk`, `timezone text`, `calendar_json jsonb`, `version int`, `active boolean`.

**`admin.settlement_cycle_profiles`** — `profile_id text pk`, `default_cycle text` (e.g. `T+1`), `rules_json jsonb`, `version int`, `active boolean`.

**`admin.override_audit`** — `override_id uuid pk`, `actor_id text`, `reason_code text`, `note text`, `target_type text`, `target_id text`, `occurred_at timestamptz`.

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint (source for this page)
- [Runtime Schema](/schema/runtime-schema/) — canonical venue facts and lifecycle projections
- [Planned Schema](/schema/planned-schema/) — account/settlement/market_data/analytics design targets

---
title: Boundary, Auth & Admin Schema
description: API boundary guardrails, roles/actor-roles, and admin policy tables.
---

## Boundary Schema

**`boundary.api_idempotency_records`** — scoped idempotency records keyed by `(client_id, route, idempotency_key)`, with status, payload, created-at, and expiry fields. Backs the `Idempotency-Key` contract on every `/api/v1` mutation — see [API Overview](../../api/overview/).

**`boundary.api_command_captures`** — request/response capture rows for accepted command intake, including command id, request payload, status, response/error fields, correlation id, and received/update timestamps.

**`boundary.stream_command_intake`** — stream-backed intake reservations keyed by scope and idempotency key, with payload hash, command id, route, subject/stream, partition, sequence, and publish markers.

**`boundary.account_risk_controls`** and **`boundary.account_risk_decisions`** — pre-acceptance account/bot risk policy and non-allow audit facts.

**`boundary.command_circuit_breakers`**, **`boundary.instrument_price_collars`**, and **`boundary.boundary_rejections`** — fail-closed command gates and rejection audit for stream health, instrument price limits, and other boundary guardrails.

## Auth Schema

**`auth.auth_roles`** — `role_id text pk`, `permissions text`.

**`auth.auth_actor_roles`** — `actor_id text`, `role_id text`; primary key `(actor_id, role_id)`.

## Admin Schema

**`admin.post_trade_profiles`** — post-trade profile policy rows keyed by `profile_id`, with mode, settlement cycle, netting mode, ledger posting mode, policy version, active flag, and update timestamp. A partial unique index enforces only one active profile.

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint (source for this page)
- [Runtime Schema](../runtime-schema/) — canonical venue facts and lifecycle projections
- [Planned Schema](../planned-schema/) — account and future analytics/archive design targets

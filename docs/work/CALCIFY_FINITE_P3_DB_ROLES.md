# Calcify finite P3 — production database role definition (DRAFT for operator review)

Date: October 11, 2026. Status: **draft; not operator-approved**. Closes the O2 follow-up "operator-reviewed role
definition" (the CI/test fixture inferred its grants from test call sites; this document states the intended split and a
verification query). Applies to migration `scripts/dev/db/migrations/boundary/0013_calcify_finite_ingress.sql`, which
deliberately creates **no roles**.

## Model

All finite ledger writes go through 11 `SECURITY DEFINER` functions in schema `boundary` (each pinned with
`SET search_path = pg_catalog, boundary`). The four finite tables (`calcify_finite_bindings`, `_runs`, `_attempts`,
`_publications`) have `REVOKE ALL FROM PUBLIC`, and **neither login role needs any table privilege**. Access is by
`EXECUTE` on functions only.

| Role | Purpose | `EXECUTE` on |
| --- | --- | --- |
| Function owner (not a login role used by services) | Owns the tables and functions; must be trusted, because definer functions run with its rights | n/a |
| `calcify_finite_registrar` | Startup and operator control: register the opening, stage and release activation | `register`, `stages`, `release`, `snapshot` |
| `calcify_finite_runtime` | The ingress process: ownership, budgeted admission, publication bookkeeping, stop | `claim_owner`, `begin_attempt`, `authorize_send`, `ack`, `unknown`, `stop`, `snapshot`, `receipt` |

Only `snapshot` is shared (the runtime path calls it for stopped-replay and readiness). The registrar cannot admit, send or
acknowledge commands; the runtime cannot register an opening or release a stopped run. Both roles must be
`LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE`, with credentials from a secret store (never in command-line arguments or
traces; the CI fixture passes them over stdin).

## Provisioning (run by an administrator, after migration 0013)

```sql
CREATE ROLE calcify_finite_registrar LOGIN PASSWORD :'registrar_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE calcify_finite_runtime   LOGIN PASSWORD :'runtime_pw'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT USAGE ON SCHEMA boundary TO calcify_finite_registrar, calcify_finite_runtime;
GRANT EXECUTE ON FUNCTION
  boundary.calcify_finite_register(TEXT,JSONB,BYTEA,TEXT),
  boundary.calcify_finite_stages(TEXT,BIGINT,TEXT,TEXT,JSONB),
  boundary.calcify_finite_release(TEXT,BIGINT,TEXT,TEXT,JSONB),
  boundary.calcify_finite_snapshot(TEXT)
  TO calcify_finite_registrar;
GRANT EXECUTE ON FUNCTION
  boundary.calcify_finite_claim_owner(TEXT,BIGINT,TEXT),
  boundary.calcify_finite_begin_attempt(TEXT,BIGINT,JSONB),
  boundary.calcify_finite_authorize_send(TEXT,BIGINT,TEXT),
  boundary.calcify_finite_ack(TEXT,TEXT,BIGINT,BIGINT,BIGINT),
  boundary.calcify_finite_unknown(TEXT,TEXT,BIGINT),
  boundary.calcify_finite_stop(TEXT,BIGINT),
  boundary.calcify_finite_snapshot(TEXT),
  boundary.calcify_finite_receipt(TEXT,TEXT)
  TO calcify_finite_runtime;
```

(`:'registrar_pw'` style variables require the secret to reach `psql` via a mechanism that is not a process argument,
for example a file piped to stdin; see the `Provision isolated finite binding-store fixture` step in `.github/workflows/ci.yml`.)

## Verification (acceptance for this definition)

Run as an administrator. Expected result was **observed on a disposable PostgreSQL 16 database with migrations
`boundary` 0001-0013 applied and the provisioning above** (October 11, 2026):

```sql
SELECT p.proname AS fn,
  has_function_privilege('calcify_finite_registrar', p.oid, 'EXECUTE') AS registrar,
  has_function_privilege('calcify_finite_runtime',   p.oid, 'EXECUTE') AS runtime,
  has_function_privilege('public',                   p.oid, 'EXECUTE') AS public_
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'boundary' AND p.proname LIKE 'calcify_finite_%' ORDER BY 1;
```

Expected: `register`, `release`, `stages` registrar-only; `ack`, `authorize_send`, `begin_attempt`, `claim_owner`, `receipt`,
`stop`, `unknown` runtime-only; `snapshot` both; `public_` false for all 11 rows. A second query over
`has_table_privilege(role, table, 'SELECT,INSERT,UPDATE,DELETE')` for the four tables returned false for both roles, and a
third query found no `calcify_finite_*` function that was not `SECURITY DEFINER` with a pinned `search_path`.

## Accepted trust boundaries and residual risks

- **`calcify_finite_ack` trusts the caller's offset and sequence.** It checks `seq = offset + 1` and the allowed range, not
  that the broker really holds the record. A compromised runtime principal could therefore record a wrong position as
  `PUBLISHED`. The control is **detective**: startup verification compares the journal byte-for-byte to the broker history
  (`verifyPublished` in the platform runtime, `verifyFiniteObservation` in the matcher) and refuses activation on mismatch.
  Accepted for fixture scope (independent review finding P3-5); revisit before any non-fixture use.
- **`stop` is runtime-callable**, so a compromised runtime can stop the run (availability only). Release requires the registrar.
- **Definer functions run as their owner.** Owner credentials must not be used by services; review owner membership and any
  `ALTER DEFAULT PRIVILEGES` that could re-grant `EXECUTE` to `PUBLIC` on new functions.
- **Admission exhaustion is by design.** An authenticated trading party can consume the finite attempt budget with charged
  refusals; this is the contract's accounting, not a defect.
- Network: the Go matcher's TLS defaults to off and Kafka transport defaults to `SASL_PLAINTEXT`; both are fixture settings.

## Open items for the operator

1. Choose the owner role and confirm no service uses it. 2. Credential delivery and rotation for the two login roles.
3. Whether to add per-role connection limits or `pg_hba` source restrictions. 4. Sign-off of the matrix above.

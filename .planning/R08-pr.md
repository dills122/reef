Branch: codex/runtime-order-run-isolation
Commit Message
fix(runtime): isolate order ownership and history by run

PR Title
fix(runtime): isolate order ownership and history by run

PR Description
## Summary
- Fix #448: reused order IDs across runs retain independent ownership, fills, lifecycle and settlement attribution.

## What Changed
- Carry typed run/order identity through memory and Postgres storage, reads, events, command-result reloads and downstream joins.
- Add migration 0073 with proven canonical recovery and shared scoped projection SQL.
- Bind captured command scope for memory canonical replay; document and test fresh-store recovery for unknown legacy history.

## Validation
- Runtime `./gradlew check --console=plain`: 706 tests, zero failures, five explicit DB skips; Jacoco gate passed.
- Separate real Postgres focused suite: 75 tests, zero failures or skips.
- `node --test scripts/dev/db/migrate.test.mjs`: 25 passed.
- Independent review 3/3: Ready; prior findings fixed with regressions.
- TDD regressions and `git diff --check` passed; see `.planning/R08-ledger.md`.

## CI Follow-up
- Runtime/Arena images use root context and package same migration; missing migration fails explicitly.
- Arena lifecycle schema fixture uses run/order identity and retains no-op rewrite assertions.
- Both image builds passed; full real-DB schema-placement suite15 passed; tooling31 passed.

## Scope Notes
- Migration forward-only; retained history without local provenance requires verified fresh projection rebuild.
- Unknown legacy facts remain unchanged. Production cutover and performance qualification not executed.

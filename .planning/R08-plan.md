# R08 implementation plan

Canonical requirement: .planning/issues/R08.md in original issue worktree, bootstrap exact path.
Baseline41c14d6a57b0da2e5f9e8df25c626249a659eb68. Branch codex/runtime-order-run-isolation.
1. Reproduce acceptance overwrite in memory and isolated real Postgres.
2. Audit typed identity propagation, keys, queries, events, joins, dirty/rebuild paths.
3. Scoped run/order identity, regressions and migration/recovery policy without guessing legacy provenance.
4. Focused realDB and full module checks, independent review up to3; no merge/deploy.
Acceptance: same order IDs across runs and same session/instrument preserve ownership/history, replay, modify/cancel, settlement attribution, memory/PG parity. Keep command/event immutability and deterministic engine behavior.
Delivery: branch and PR-ready metadata, durable ledger. Operational production cutover outside executed scope; recovery prerequisites/limits must be concrete.

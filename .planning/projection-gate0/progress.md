# Projection Gate 0 Progress

## Log

- 2026-08-20: initialized the approved Gate 0 delivery plan. No runtime or
  benchmark implementation changed yet.
- 2026-08-20: added a startup safety guard for command-status plus lifecycle or
  market-data consumers, with an explicit diagnostic-only escape hatch.
- 2026-08-20: added projector batch metrics and phase timers for canonical
  reads, transform/serialization, projection SQL, watermark updates, and
  commits.
- 2026-08-20: extended stress telemetry and the evidence checker to capture DB
  pool and hot-path snapshots from every projector.
- 2026-08-20: added a fixed-canonical-ceiling projection drain benchmark and
  deterministic report calculations. No DigitalOcean infrastructure was run.
- 2026-08-20: full platform-runtime tests, focused Node regression tests,
  script-surface checks, shell syntax, Compose rendering, developer-tooling
  tests, and `git diff --check` passed. Gate 0 implementation is complete;
  live local drain evidence was the next operational step at that point.
- 2026-08-20: live validation used an isolated Compose project and volumes.
  The successful measured window drained `52,250` projected work items to zero
  in `13.297s`, kept every canonical partition ceiling stable, and recorded no
  failures, retries, pool waiters, or dirty-queue residue. Existing Reef
  containers and data were not modified.
- 2026-08-20: removed the isolated benchmark containers, network, and
  project-scoped volumes after preserving the reports under `/tmp`; verified
  the normal Reef Postgres, projection-Postgres, and boundary-Postgres
  containers remained healthy and retained their original uptime.
- 2026-08-20: added the remaining query-attribution path for the named remote
  projection gate: benchmark-only `pg_stat_statements` with nested-statement
  tracking, I/O/WAL-I/O timing, pre/post statement/settings artifacts,
  statement deltas, and fail-closed evidence requirements. A disposable local
  PostgreSQL validation captured a function and its nested insert separately;
  the isolated container and volume were then removed. No paid run was made.
- 2026-08-20: ran a fresh isolated one-maintainer cardinality A/B. The corrected
  topology projected all `100,002` fixed outcomes within `10.383s` of container
  start (`>=9,631.32/s`), cleared both dirty queues, and had no
  failures/retries/deadlocks. Added per-projector lifecycle/market-data Compose
  controls and made the named remote gate select projector 0 as the sole
  maintainer. The effective flags are exposed by projector status, retained in
  stress artifacts, and checked fail-closed for exactly one maintainer of each
  type. The isolated project and volumes were removed. No paid run was made.
- 2026-08-20: final proportional verification passed: full platform-runtime
  tests, benchmark checker and named-gate tests, database-diagnostics and drain
  calculation tests, developer-tooling tests, script-surface validation,
  Compose rendering, shell syntax, and whitespace validation. Reconfirmed the
  normal Reef database containers remain healthy with unchanged uptime.

# Command Log Partitioning Plan

## Purpose

Define the partitioning path for `command_log` after the prune and retention-pin lifecycle controls.

The goal is to keep high-throughput intake fast while preserving command capture, idempotency replay, auditability, and named simulation/replay retention.

## Current Shape

Current live tables:

- `command_log.commands`
  - durable intake row
  - `command_id` primary key
  - unique `(client_id, route, idempotency_key)`
  - contains request payload and command metadata
- `command_log.command_work_queue`
  - active worker state only
  - references `commands(command_id)` with `ON DELETE CASCADE`
- `command_log.command_results`
  - terminal status/response payload
  - references `commands(command_id)` with `ON DELETE CASCADE`
- `command_log.retention_pins`
  - protects named command history from pruning

Measured pressure:

- loaded local command-log history exceeded `2M` rows
- prune recovered throughput from `1815.38` to `3477.43` accepted rps on the same loaded stack
- cleanup did not recover the earlier `4020.17` accepted rps split-schema baseline
- hot-path latency still concentrates in `api.commandCapture.reserve`

## Constraints

1. Keep `command_id` lookup simple.
- status APIs, async workers, and duplicate replay all depend on fast command lookup.

2. Keep idempotency unique.
- `(client_id, route, idempotency_key)` must remain globally unique for live command intake.

3. Do not break active queue semantics.
- pruning or partitioning must not delete commands with active `command_work_queue` rows.

4. Preserve pinned history.
- retained runs must survive local cleanup and future partition lifecycle operations.

5. Avoid risky in-place native partitioning of `commands`.
- PostgreSQL unique constraints on partitioned tables must include the partition key.
- range partitioning `commands` by `received_at` would force primary/unique keys to include `received_at`, which complicates `command_id` foreign keys and current lookup semantics.

## Decision

Do not range-partition the current `command_log.commands` table in place.

Instead:

1. Keep a narrow live command index table for hot lookup and idempotency.
2. Move bulky terminal history into partition-friendly history/archive tables.
3. Keep active queue rows in a small active-only table.
4. Use retention pins to protect named runs before archive/drop operations.

This preserves the stable hot path while allowing old terminal data to be dropped or archived by partition.

## Target Shape

```text
command_log.commands
  hot intake/idempotency index
  command_id PK
  client_id, route, idempotency_key unique
  minimal metadata needed for replay/status routing

command_log.command_payloads
  payload by command_id for active and recent commands
  candidate for time/run partitioning after lookup is stable

command_log.command_work_queue
  active rows only
  no terminal rows

command_log.command_results_live
  recent terminal results needed for status replay
  small retention window

command_log.command_results_archive
  partitioned terminal history
  partition key: completed_at
  optional sub-key later: run/session

command_log.retention_pins
  protects command_id or idempotency/session prefixes
```

## Migration Phases

### P0: Lifecycle Controls

Status: done.

- dry-run-first terminal prune
- retention pins
- DB diagnostics on throughput runs

### P1: Add Run/Session Attribution

Add explicit run/session metadata before partitioning by run.

Candidate columns:

- `run_id TEXT NOT NULL DEFAULT ''`
- `run_kind TEXT NOT NULL DEFAULT ''`
- `scenario_id TEXT NOT NULL DEFAULT ''`

Initial source:

- intake/load-test session IDs
- simulator scenario IDs
- later bot arena competition IDs

Acceptance criteria:

- every load/intake report records the run ID used for commands
- diagnostics can group command-log growth by run ID
- prune can protect by `run_id` or retention pin

### P2: Split Payload From Hot Command Index

Move request payload out of `commands` into `command_payloads`.

Reason:

- `payload_json` makes the command intake table physically heavy
- async workers need payload only until processing completes
- old payload history is better suited to archive retention than hot lookup

Target:

- `commands` keeps command metadata and idempotency
- `command_payloads(command_id, received_at, payload_json)`
- active/recent payloads remain available to async workers
- terminal/pinned payloads can be archived

Acceptance criteria:

- `append`, duplicate replay, `claimReceived`, and status lookup remain behaviorally identical
- hot-path reserve latency does not regress by more than 10%
- diagnostics show slower growth in `command_log.commands`

### P3: Archive Terminal Results By Time

Introduce partitioned archive tables for terminal history.

Candidate:

- `command_results_archive`
- partition key: `completed_at`
- partition size: daily for stress/dev, weekly/monthly for longer scenarios

Flow:

1. terminal result lands in live result table
2. archive job copies terminal rows older than a live retention window
3. archive job verifies row counts/checksums
4. prune deletes live command/result rows not protected by pins
5. old archive partitions can be detached/dropped/exported

Acceptance criteria:

- dropping an old archive partition is O(1) relative to row count
- pinned commands are excluded from archive-drop cleanup
- status APIs clearly define whether archived commands are queryable online

### P4: Optional Native Partitioning For New V2 Tables

Only partition tables whose constraints include the partition key naturally.

Good candidates:

- archive/result history by `completed_at`
- payload archive by `received_at`
- run-scoped archive by `run_id` if run ID becomes the primary lifecycle unit

Poor candidates:

- current `commands` table by `received_at`
- active queue by time

Reason:

- current command lookup and FK semantics are command-ID centered
- active queue should stay small through lifecycle discipline, not partitioning

## Benchmark Protocol

Every partitioning or archive slice must run:

1. pre-change loaded-stack diagnostics
2. clean DB intake benchmark
3. warm DB intake benchmark
4. loaded DB intake benchmark
5. post-change diagnostics delta

Required metrics:

- accepted rps
- p50/p95/p99
- `api.commandCapture.reserve`
- async claim/complete latency
- command-log table/index byte deltas
- live/dead tuple deltas
- queue drain time

## Open Questions

1. Should archived commands remain queryable through the same status API?
- recommendation: not in the first partitioning slice
- expose explicit archive/replay tooling instead

2. Should run/session attribution be mandatory for public clients?
- recommendation: no
- default to empty run metadata for ordinary clients, require it for simulator/arena workloads

3. Should command payloads be retained by default?
- recommendation: retain recent and pinned payloads; export or prune unpinned stress payloads

4. Should physical database split happen before partitioning?
- recommendation: no
- keep schema-level lifecycle controls first

## Next Slice

Implement P1 run/session attribution in command-log intake and benchmark reports.

The smallest useful change:

- add nullable/defaulted `run_id` metadata to `command_log.commands`
- let intake/load-test scripts pass a run ID through request metadata or payload extraction
- extend diagnostics/prune reports to show command-log growth by run ID
- add retention pin selector `run_id`

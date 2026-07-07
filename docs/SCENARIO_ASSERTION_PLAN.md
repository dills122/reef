# Scenario Assertion Plan

## Purpose

Define how first-wave scenarios move from fixture-aligned to locked against live platform facts.

The YAML fixtures and dry-run smoke goldens prove request shape. They do not prove lifecycle correctness, visibility rules, durable replay, projection freshness, or settlement causation. A scenario is locked only when the live assertion report passes and records the facts used to prove it.

## Assertion Tool Shape

Add a live assertion harness as `scenario-assert`, or extend `scenario-smoke` with an assertion mode if that keeps the implementation smaller.

Required behavior:

1. Load the scenario YAML.
2. Compile the same executable commands used by `scenario-smoke`.
3. Seed reference/auth data when requested.
4. Submit executable commands through public command APIs.
5. Wait for command status completion.
6. Query the configured read surfaces.
7. Run scenario-specific assertions.
8. Run or attach replay/checksum evidence.
9. Emit one JSON assertion report.

Dry-run smoke remains useful for request/golden stability. It is not a locking gate.

## Lock States

| State | Meaning |
| --- | --- |
| `fixture-aligned` | YAML, dry-run smoke, and tests encode the agreed scenario shape. |
| `live-asserted` | Public commands were submitted and live platform reads proved the scenario facts. |
| `replay-asserted` | Replay/checksum tooling proved durable fact identity for the same run. |
| `locked` | `live-asserted` and `replay-asserted` both pass with artifacts checked into or linked from evidence. |

## P1 Lock Criteria

`P1_GOLDEN_HIDDEN_CROSS_T1` locks only when all checks pass:

- three submit commands are accepted and complete
- hidden sell order final lifecycle state is `FILLED`
- first visible buy final lifecycle state is `FILLED`
- second visible buy final lifecycle state is `FILLED`
- first visible buy fills quantity `40`
- second visible buy fills quantity `60`
- exactly two executions exist for the scenario run
- exactly two public trade facts exist for the scenario run
- total executed quantity is `100`
- both execution/trade prices are `100.00`
- public top-of-book/depth never exposes the hidden resting sell size before execution
- hidden seller own-order read can see its own hidden order and lifecycle state
- trade tape shows both trades without counterparty identity
- replay/checksum evidence proves identical command outcome order, execution/trade facts, lifecycle states, and checksums

P1 is a hard venue-core correctness gate. It must not rely on settlement or post-trade stubs.

## P2 Lock Criteria

`P2_SETTLEMENT_BREAK_REPAIR` locks only when all checks pass:

- one canonical trade exists or is referenced by the scenario
- one settlement obligation references that canonical trade
- one break exists with reason `CASH_LEG_FAILED`
- one manual repair event exists
- final settlement state is `RESOLVED`
- final exception state is `RESOLVED`
- no failed-to-resolved transition exists without a repair event
- no real account-ledger mutation is required or implied by this first slice
- all settlement facts carry `scenarioRunId`, `correlationId`, and `causationId`

P2 proves causation only:

```text
trade -> obligation -> cash-leg break -> repair -> resolved
```

It does not authorize broad allocation, confirmation, clearing, account-ledger, or exception UI work.

## Authoritative Reads

Use these surfaces as the first proof sources:

| Assertion area | Preferred proof source | Notes |
| --- | --- | --- |
| command completion | `GET /api/v1/commands/{commandId}` | Must distinguish accepted, in-flight, completed, rejected, and failed states. |
| own-order lifecycle | `/api/v1/orders/current`, `/api/v1/orders/history` | Participant-scoped; must include freshness/projection metadata where available. |
| public depth visibility | `/api/v1/market-data/depth/{instrumentId}` | Proves public hidden-liquidity visibility rules. |
| public trade tape | `/api/v1/market-data/trades/{instrumentId}` | Must exclude counterparty/order/participant identity. |
| read-surface inventory | `/api/v1/data/availability` | Report must record source type, freshness model, lag/watermark where available. |
| replay/checksum | `make dev-venue-event-replay-check` or successor command | Must report zero gaps, duplicate inserts, payload mismatches, stream gaps, overlaps, missing rows, and extra rows. |
| settlement facts | new settlement assertion query or test-only assertion read | Required before P2 can be locked. Keep this narrow to [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md). |

If a read is projection-backed, lag and watermark metadata must be recorded. Projection lag may not be hidden by retry loops.

## Timing Defaults

Use these defaults unless a profile-specific plan overrides them:

- command completion wait: `30s`
- projection/read freshness wait: `30s`
- replay/checksum wait: `60s`

Failures after timeout are hard failures. A report may classify the cause as command completion, projection freshness, replay/checksum, settlement fact missing, or visibility mismatch.

## Assertion Report Contract

The report must be JSON and include:

```json
{
  "pathId": "P1_GOLDEN_HIDDEN_CROSS_T1",
  "scenarioRunId": "example-run",
  "seed": 424242,
  "mode": "live",
  "pass": true,
  "commands": [],
  "reads": [],
  "assertions": [],
  "failures": [],
  "projectionLag": [],
  "replayChecksum": {},
  "artifactPaths": []
}
```

Required field meaning:

- `commands`: submitted command id, route, final status, timing, and status source
- `reads`: endpoint, filters, source type, freshness model, lag/watermark, response artifact path
- `assertions`: assertion id, status, expected value, observed value, and proof source
- `failures`: assertion id, category, message, and proof source
- `projectionLag`: projection name, lag, watermark, measuredAt
- `replayChecksum`: command/event counts, checksums, gap counts, duplicate counts, and replay command used
- `artifactPaths`: local paths for the report, command log extracts, read responses, and replay/checksum outputs

## First Implementation Slices

1. Add assertion report types and dry-run JSON shape. Initial implementation extends `scenario-smoke` with `--live --assertions`, preserving dry-run smoke output unless assertions are requested.
2. Add P1 command completion and own-order lifecycle assertions. Initial implementation records command status proof from `GET /api/v1/commands/{commandId}` and participant-scoped order history proof from `/api/v1/orders/history`.
3. Add P1 trade tape and public depth assertions. Initial implementation records public trade tape proof from `/api/v1/market-data/trades/{instrumentId}` and current public depth non-leak proof from `/api/v1/market-data/depth/{instrumentId}`; historical pre-execution hidden-depth proof still needs timeline/replay evidence because the current depth endpoint is not time-travel capable.
4. Attach replay/checksum evidence to the P1 report.
5. Add narrow P2 settlement fact assertion source.
6. Add P2 obligation, `CASH_LEG_FAILED`, repair, and resolved-state assertions.
7. Promote passing reports into the active evidence docs.

## Non-Goals

- no new scenario command path separate from public APIs
- no broad settlement/account-ledger implementation for P2
- no UI-only proof as a locking gate
- no passing a scenario when projection lag remains unknown
- no treating dry-run smoke as live correctness evidence

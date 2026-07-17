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
- replay/checksum evidence proves identical command outcome order, execution/trade facts, lifecycle states, checksums, and historical public-depth hidden-size non-exposure

P1 is a hard venue-core correctness gate. It must not rely on settlement or post-trade stubs.

## P2 Lock Criteria

`P2_SETTLEMENT_BREAK_REPAIR` locks only when all checks pass:

- one canonical trade exists or is referenced by the scenario
- one settlement obligation references that canonical trade
- one break exists with reason `CASH_LEG_FAILED`
- one manual repair event exists
- final settlement state is `RESOLVED`
- final exception state is `RESOLVED`
- exception queue has one resolved settlement-break row, no open rows, `ownerRole = SETTLEMENT_OPS`, `actionRequired = NONE`, and repair action matching the break reason
- no failed-to-resolved transition exists without a repair event
- historical: the first slice required or implied no real account-ledger mutation. `D-050` has since authorized real ledger mutation broadly, and obligation materialization now writes real append-only ledger entries for settled instant-post-trade obligations; this criterion no longer reflects current scope (see [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md#obligation-materialization))
- all settlement facts carry `scenarioRunId`, `correlationId`, and `causationId`

P2 proves causation only:

```text
trade -> obligation -> cash-leg break -> repair -> resolved
```

P2 did not by itself authorize broad allocation, confirmation, clearing, account-ledger, or exception UI work. `D-050` has since accepted account-ledger mutation and obligation materialization as shipped scope. The next narrow post-trade assertion now accepts a settled instant-post-trade chain when facts prove:

```text
trade -> obligation -> allocation -> confirmation -> affirmation -> clearing submission -> clearing acceptance -> novation -> instruction -> attempt -> settled
```

The assertion requires one allocation, one confirmation, one affirmation, one clearing submission, one clearing acceptance, zero clearing rejections, one novation, one instruction, one attempt, and one settled finality fact with exact states `ALLOCATION_PROPOSED`, `CONFIRMATION_GENERATED`, `AFFIRMATION_ACCEPTED`, `CLEARING_SUBMITTED`, `CLEARING_ACCEPTED`, `NOVATION_RECORDED`, `INSTRUCTION_CREATED`, `ATTEMPT_STARTED`, and `SETTLED`; allocation must reference the canonical trade and buy/sell order ids, confirmation must be caused by allocation, affirmation by confirmation, clearing submission by affirmation, clearing acceptance by clearing submission, novation by clearing acceptance, instruction by novation, attempt by instruction, and settlement by attempt. Full allocation/confirmation/clearing workflows and exception UI remain later work unless separately planned.

## Authoritative Reads

Use these surfaces as the first proof sources:

| Assertion area | Preferred proof source | Notes |
| --- | --- | --- |
| command completion | `GET /api/v1/commands/{commandId}` | Must distinguish accepted, in-flight, completed, rejected, and failed states. |
| own-order lifecycle and fills | `/api/v1/orders/current`, `/api/v1/orders/history`, `/api/v1/orders/fills` | Participant-scoped; must include freshness/projection metadata where available and must not expose counterparty identity in own fill reads. |
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
2. Add P1 command completion, own-order lifecycle, and own-fill assertions. Current implementation records command status proof from `GET /api/v1/commands/{commandId}`, participant-scoped order history proof from `/api/v1/orders/history`, and participant-scoped fill proof from `/api/v1/orders/fills`, including final lifecycle state, filled quantity for all three P1 orders, exactly two unique execution IDs across participant fill reads, expected fill prices, and no counterparty fields in own-fill payloads. When command status exposes canonical payload or stream scope, the harness now asserts that the embedded `runId`/`scenarioRunId` matches the current report so retained durable streams cannot satisfy a new run through stale deterministic command ids. Durable replay lock attempts should use `--run-scoped-command-ids` to prefix executable command ids and idempotency keys with the current scenario run while leaving order ids, trade ids, and scenario metadata stable. Clean-stack lock runs can add `--require-zero-projection-lag`; retained-stream runs should rely on exact replay-check event stream and command-id filters until `/api/v1/data/availability` can scope lag by event stream.
3. Add P1 trade tape, public depth, and read-surface inventory assertions. Current implementation records public trade tape proof from `/api/v1/market-data/trades/{instrumentId}`, captures a `visibilityTimeline` public-depth probe after the hidden sell is accepted and before the first visible buy is submitted, records end-state public depth non-leak proof from `/api/v1/market-data/depth/{instrumentId}`, and records source/freshness/projection-lag proof from `/api/v1/data/availability` for order history, order fills, market depth, and trade tape.
4. Attach replay/checksum and historical visibility evidence to the P1 report. Current implementation accepts a `--replay-check-report` JSON artifact from `make dev-venue-event-replay-check`, stores it in `replayChecksum`, records its path, and fails the live assertion report when the replay check reports failures. In strict P1 mode, `visibilityTimeline.publicDepthHiddenRestingExposed=false` and at least one `visibilityTimeline.publicDepthChecks` row are required; the smoke report now supplies that timeline and folds it into `replayChecksum` when the replay artifact omits it. The replay-check script can still bundle that timeline from `DEV_VENUE_EVENT_REPLAY_CHECK_VISIBILITY_TIMELINE_JSON` or `DEV_VENUE_EVENT_REPLAY_CHECK_VISIBILITY_TIMELINE_PATH`.
5. Add narrow P2 settlement fact assertion source. Current implementation reads `/api/v1/settlement/facts/{scenarioRunId}` by default and keeps `--settlement-facts-report` as an offline artifact fallback, with obligation, break, repair, and resolution facts shaped by [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md), plus the minimal allocation/confirmation/affirmation/clearing/novation settled chain when those facts are present.
6. Add P2 obligation, `CASH_LEG_FAILED`, repair, resolved-state, exception-queue, and minimal settled-chain assertions. Current implementation checks one obligation with `tradeId`, one `CASH_LEG_FAILED` break, one `POST_CASH_LEG_REPAIR`, final settlement/exception `RESOLVED`, no resolution without repair linkage, and causation fields on every settlement fact. It also reads `/api/v1/settlement/exceptions/{scenarioRunId}` for live P2 assertions and requires one resolved settlement-break queue row, zero open rows, no clearing-rejection rows, `ownerRole = SETTLEMENT_OPS`, `actionRequired = NONE`, reason-matched repair action, and linked break/repair/resolution/actor/correlation/resolvedAt fields. For settled instant-post-trade facts, it checks `trade -> obligation -> allocation -> confirmation -> affirmation -> clearing submission -> clearing acceptance -> novation -> instruction -> attempt -> settled` references and causation ids, including canonical buy/sell order ids on allocation, plus exact intermediate/finality states for obligation, allocation, confirmation, affirmation, clearing submission, clearing acceptance, novation, instruction, attempt, and settlement.
7. Promote passing reports into the active evidence docs.

Current local gate note: the P1 assertion harness carries client, participant, and actor identity headers across command status and read assertions, preflights `/readyz` before `--assertions`, accepts provider-neutral `sync-result` command-capture status, and polls projection-backed own-order reads until lifecycle state settles. Live reference/auth seeding now uses `/admin/v1/reference/...` and `/admin/v1/auth/...`; set `ADMIN_API_TOKEN` when the local admin gateway requires it. Assertion reports redact `Authorization` headers before writing stdout or `--report-out`.

On 2026-07-14, P1 passed locally on a `sync-result` stack started with `ORDER_LIFECYCLE_PROJECTOR_ENABLED=true` and `MARKET_DATA_PROJECTOR_ENABLED=true`; report: `reports/scenario-assertions/p1-golden-hidden-cross-live-20260714.json`. The report passed 25 assertions and proves the P1 scenario trades by deterministic trade ids even when the instrument-level `XYZ` public tape also contains an unrelated P2 trade. A later direct-stream replay attempt exposed a lock gap: `/api/v1/commands/{commandId}` can return a prior canonical outcome when the same deterministic command ids exist in retained streams, so replay promotion must use `--run-scoped-command-ids` or otherwise prove command-status evidence is scoped to the current scenario run/event stream.

On 2026-07-15, the direct-stream same-run P1 report at `reports/scenario-assertions/p1-golden-hidden-cross-replay-live-20260714.json` passed with 44 assertions after attaching `reports/scenario-assertions/p1-golden-hidden-cross-replay-check-20260714.json`. It combines run-scoped command ids, canonical command status from `platform-projector-0`, exact replay counters for the same three P1 commands, native `visibilityTimeline` proof, and replay-folded hidden-depth visibility proof. The report records `/api/v1/data/availability` projection lag rows of `4`; that is retained-stack lag evidence, not a zero-lag claim. P1's local lock uses the 2026-07-14 `sync-result` report for zero-lag projection freshness and this direct-stream report for same-run durable replay proof.

On 2026-07-14, P2 command smoke passed locally; report: `reports/scenario-assertions/p2-settlement-break-repair-live-20260714-command-smoke.json`. P2 assertion also passed locally after seeding facts with `make dev-seed-p2-settlement-facts SCENARIO_RUN_ID=p2-settlement-break-repair-live-20260714`; report: `reports/scenario-assertions/p2-settlement-break-repair-live-20260714.json`. The report passed 18 assertions for the exception chain.

On 2026-07-15, P2 direct-stream settled-chain evidence passed locally with public settlement facts readback. The report at `reports/scenario-assertions/p2-settlement-break-repair-settled-live-public-20260715c.json` passed after attaching replay check `reports/scenario-assertions/p2-settlement-break-repair-replay-check-20260715c.json`; the public readback artifact is `reports/scenario-assertions/p2-settlement-break-repair-settled-facts-public-20260715c.json`. It proves run-scoped command completion from canonical command status, clean replay counters for the same two commands, and exact intermediate/finality states for obligation, allocation, confirmation, affirmation, instruction, attempt, leg outcomes, ledger entries, and `SETTLED` finality from `/api/v1/settlement/facts/{scenarioRunId}`.

## Non-Goals

- no new scenario command path separate from public APIs
- historical: P2 itself did not authorize broad settlement/account-ledger implementation. `D-050` has since authorized and shipped real account-ledger mutation via obligation materialization, so this is no longer a current restriction (see [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md)); broad allocation/confirmation/clearing/exception-UI work remains unplanned unless separately scoped
- no UI-only proof as a locking gate
- no passing a scenario when projection lag remains unknown
- no treating dry-run smoke as live correctness evidence

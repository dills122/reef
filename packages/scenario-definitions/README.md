# Scenario Definitions

Reusable scenario packs for deterministic lifecycle simulation.

## Rules

- keep scenarios deterministic
- track seeds and scenario metadata explicitly
- ensure scenarios drive the platform through real command paths
- prefer additive evolution of scenario specs; do not break existing `schemaVersion` behavior

## Layout

```text
scenarios/
  v1/                                  lifecycle scenario specs (P1, P2, ...)
arena/
  actor-profiles.v1.json               Bot Arena actor profiles
  bot-catalog.v1.json                  Bot Arena bot catalog
  equity-multi-local.v1.json           game-mode definitions
  equity-sprint.v1.json
  economics/                           fee and liquidity-subsidy presets
  scoring/                             score-v0.json, score-v1.json
persona-session.example.yaml           example persona session
stream-ack-submit-stress.yaml          stream-ack submit stress workload
```

The `scenarios/v1` specs follow the template below. The `arena/` JSON and the
root-level YAML files are consumed by `scripts/dev` tooling and use their own shapes.

## Spec template

Each scenario file should include:

- `schemaVersion`
- `pathId`
- `name`
- `businessGoal`
- `seed`
- `preconditions`
- `runContext`
- `faultInjection`
- `steps`
- `expectedEvents`
- `expectedEventTimeline` for first-wave and golden paths that assert replay timestamp order
- `expectedFinalStates`
- `invariants`
- `uiAssertions`
- `replayAssertions`
- `idempotencyAssertions`

## Notes

- `steps` are logical command-stage definitions, not direct database actions.
- `steps` must be ordered with contiguous `sequence` values starting at `1`.
- `expectedEvents` order is authoritative for replay validation.
- `expectedEventTimeline` uses scenario-start-relative `occurredAtOffsetSeconds`
  values and must match `expectedEvents` order when present.
- `scenarioRunId`, `seed`, `correlationId`, and `causationId` are required metadata in scenario-driven events.
- First-wave target contracts are defined in [`../../docs/SCENARIO_CONTRACTS.md`](../../docs/SCENARIO_CONTRACTS.md). Live lock criteria are defined in [`../../docs/SCENARIO_ASSERTION_PLAN.md`](../../docs/SCENARIO_ASSERTION_PLAN.md). When YAML fixtures differ from those documents, update fixtures and golden artifacts deliberately before treating a scenario as locked.

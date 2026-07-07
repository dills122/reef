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
  v1/
    P1_GOLDEN_HIDDEN_CROSS_T1.yaml
    P2_SETTLEMENT_BREAK_REPAIR.yaml
```

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

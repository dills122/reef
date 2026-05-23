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
- `expectedFinalStates`
- `invariants`
- `uiAssertions`
- `replayAssertions`
- `idempotencyAssertions`

## Notes

- `steps` are logical command-stage definitions, not direct database actions.
- `expectedEvents` order is authoritative for replay validation.
- `scenarioRunId`, `correlationId`, and `causationId` are required metadata in scenario-driven events.

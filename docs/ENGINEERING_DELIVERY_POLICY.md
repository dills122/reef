# Reef Engineering Delivery Policy

## Purpose

Define how Reef balances net-new feature delivery with refactors and test hardening without compromising quality or velocity.

## Non-Negotiable Test Rule

- every new feature must include tests for newly introduced behavior
- this applies in every delivery mode
- test hardening is additive and does not replace tests for new features

## Definition of Test Hardening

Test hardening means improving existing test reliability and depth, including:
- reducing flaky tests
- adding edge-case coverage to existing paths
- strengthening deterministic/replay assertions
- improving golden fixtures and regression checks

## Delivery Modes

### Feature-Heavy Mode
- split: `85/10/5` (core/refactor/hardening)
- use when deadlines are tight and quality indicators are stable

### Balanced Mode (Default)
- split: `70/20/10`
- use for normal roadmap execution

### Stabilization/Refactor Mode
- split: `40/40/20` or `30/50/20`
- use when delivery friction/regression risk is rising

## Mode Selection Matrix

Use a binary scorecard each sprint planning (0/1 per indicator).

Switch to a mode when 3 or more indicators in that direction are true.

Indicators favoring Feature-Heavy Mode:
- lead time is stable
- escaped-defect rate is low
- CI reliability is high
- most PRs are localized with low blast radius
- low rework from recent merges

Indicators favoring Stabilization/Refactor Mode:
- repeated defects in same subsystem
- increasing hotfix/rollback frequency
- flaky tests or low CI confidence
- high blast radius for small feature changes
- recurring workaround/plumbing edits blocking velocity

## Refactor Trigger Rule

- if a feature requires more than 2 invasive edits in a high-churn core file/module, execute enabling refactor first

## Definition of Done Gate (Per Feature)

- tests added/updated for new behavior
- validation updated for any new input shape
- observability/attribution updated for new runtime dimensions where relevant
- no regression in legacy/compatible behavior paths


# Simulator Upgrade Backlog

## Purpose

Capture post-v1 simulator enhancements, known pitfalls, and an execution policy for balancing new feature delivery with refactor debt.

## Current Status (as of 2026-05-23)

Implemented:
- `--session-config` (YAML/JSON) parsing and validation
- config + CLI override precedence
- actor-weighted routing from session actors
- multi-instrument seeding and actor symbol eligibility routing
- actor/strategy attribution in JSON summary
- deterministic decision-sequence test coverage for fixed seeds

Still pending for core persona/session upgrade:
- real strategy module execution (strategy behavior tied to `strategyId`, not profile fallback)
- `strategyProfiles` config resolution + strict reference validation
- `actorGroups` deterministic expansion
- deterministic `faults` execution engine
- replay pack/golden scenario regression harness
- pretty console attribution output (`byActor`, `byStrategy`)

## Nice-To-Haves (Future)

### Tier 1: High-Leverage
- multi-seed batch runner with aggregate reports
- scenario baseline drift detection (compare against saved golden summaries)
- richer replay artifact bundle (decision stream + summary + trace sample)

### Tier 2: Realism and Operability
- regime-aware volatility/spread shifts over session clock
- liquidity tiers by symbol/actor type
- fault library (latency spikes, endpoint degradation, reject bursts)
- actor timeline export for post-run explainability

### Tier 3: UX and Integrations
- docs-site scenario catalog pages generated from scenario files
- run tags/metadata for CI and benchmark dashboards
- optional stochastic mode (non-deterministic jitter) for long soak tests

## Learnings and Pitfalls

- determinism is fragile: avoid hidden entropy (`time.Now()` seeds, iteration over maps, scheduling side effects)
- override precedence must remain single-sourced; scattered merges create subtle config bugs
- mixed legacy/session paths in one file increase branching complexity and regression risk
- weak validation creates expensive runtime debugging
- attribution retrofits are costly; metadata should be mandatory early in request construction

## Refactor Candidates

### Immediate
- split `services/simulator/cmd/load-tester/main.go` into packages:
  - `internal/runtime`
  - `internal/actor`
  - `internal/strategy`
  - `internal/report`
  - `internal/transport`
- extract decision engine interfaces for deterministic unit tests independent of HTTP
- move summary aggregation into standalone report package with focused tests

### Near-Term
- replace ad hoc payload maps with typed request builders
- split legacy profile runner and session runner behind a shared runner interface
- centralize config resolution/override policy in `internal/config/resolver.go`

## Execution Policy: New Work vs Refactors

Use a 70/20/10 allocation per sprint:
- 70%: net-new simulator capability on critical path
- 20%: structural refactors that reduce immediate delivery risk
- 10%: hardening/tests/cleanup

Refactor trigger rule:
- if a feature needs more than 2 invasive edits in `main.go`, do the enabling refactor first

Non-negotiable test rule:
- all new feature work must include tests for the newly introduced behavior
- this rule applies in every delivery mode (feature-heavy, balanced, stabilization)

Definition of test hardening:
- test hardening does not replace tests for new features
- test hardening means improving existing test reliability, depth, and coverage
- examples: reducing flaky tests, adding edge-case coverage to existing paths, stronger determinism assertions, improving fixtures/golden comparisons

Definition of Done gate for each new simulator feature:
- deterministic behavior test added or updated
- summary/report attribution updated if new runtime dimension is introduced
- config validation updated for any new input shape
- no regressions in legacy profile mode

## Delivery Mode Matrix

Default mode:
- balanced mode (`70/20/10`)

Feature-heavy mode:
- split: `85/10/5`
- use when: deadline pressure is high and quality indicators are stable

Balanced mode:
- split: `70/20/10`
- use when: roadmap delivery is steady with moderate complexity/risk

Stabilization/refactor mode:
- split: `40/40/20` or `30/50/20`
- use when: delivery friction and regression risk are materially increasing

Mode selection indicators:
- use a binary scorecard each sprint planning (0/1 per indicator)
- switch to a mode when 3+ indicators in that direction are true

Indicators favoring feature-heavy mode:
- lead time is stable
- escaped-defect rate is low
- CI/test reliability is high
- most PRs are localized with low blast radius
- little rework from recent feature merges

Indicators favoring stabilization/refactor mode:
- repeated defects in the same subsystem
- increasing hotfix/rollback frequency
- test flakiness or low CI confidence
- high blast radius for small feature changes
- recurring workaround/plumbing edits blocking feature velocity

## Recommended Sequence (Next)

1. `strategyProfiles` resolution + strategy module abstraction
2. `actorGroups` deterministic expansion
3. replay harness + golden scenario tests
4. deterministic faults engine
5. runner/package refactor completion
6. realism nice-to-haves (regimes/liquidity/fault library expansion)

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
- persona attribution in JSON summary
- pretty summary attribution for actors/strategies/personas
- deterministic decision-sequence test coverage for fixed seeds
- strategy behavior resolution by `strategyId` (direct and profile-backed)
- `strategyProfiles` strict validation
- `actorGroups` deterministic expansion
- deterministic faults support for `reject_submit`, `reject_modify`, `reject_cancel`
- replay pack/golden scenario regression harness (`make dev-replay`)
- reject taxonomy breakdown in run summary
- stress telemetry capture (Docker + runtime/engine endpoint probes)
- worker sweep + recommendation artifact generation
- strict-lifecycle min-live-order gating and adaptive recovery guardrails
- initial runtime system tuning pass (indexes + runtime/db concurrency defaults)

Still pending for the next wave:

- quality-lane + capacity-lane throughput campaign automation and baseline publication
- stricter lifecycle-valid traffic shaping to consistently exceed `>=90%` success
- configurable circuit-breaker framework for junk-traffic throttling/blocking
- deeper runtime hot-path optimization after lane baselines are locked

## Stress Findings (2026-05-23)

### Throttled Ramp (`20s`, `workers=32`, `capacity-baseline`)

- `rate=500`: throughput `469.09 rps`, accepted `434.93 rps`, fail rate `7.28%`
- `rate=1000`: throughput `869.69 rps`, accepted `808.96 rps`, fail rate `6.98%`
- `rate=2000`: throughput `876.50 rps`, accepted `813.10 rps`, fail rate `7.23%`

Finding:

- on this dev stack shape, throttled runs saturate around `~870-880 rps` total and `~810-813 rps` accepted business ops.

### Unthrottled Ceiling Sweep (`20s`, `capacity-baseline`)

- `workers=32`: throughput `832.21 rps`, accepted `772.31 rps`, p95 `56.85ms`, p99 `59.25ms`
- `workers=64`: throughput `1579.09 rps`, accepted `1469.58 rps`, p95 `59.08ms`, p99 `73.00ms`
- `workers=96`: throughput `1818.47 rps`, accepted `1684.28 rps`, p95 `82.14ms`, p99 `127.17ms`

Finding:

- higher unthrottled throughput is achievable by raising worker parallelism, but latency tails degrade materially at `workers=96`.

### Resource Snapshot Notes

- point-in-time `docker stats --no-stream` snapshots show memory growth and DB block I/O growth across tiers.
- snapshots are not peak CPU telemetry; add continuous sampling/profiling for bottleneck attribution.

## Follow-Up Enhancements From Stress Runs

- [x] continuous runtime/engine/db telemetry capture during stress runs
- [x] worker-sweep automation with recommendation output
- [x] strict-lifecycle stress profile option (`strict-clean`)
- [x] explicit reject taxonomy percentages in reports
- [ ] throughput lane baseline pack (`quality` vs `capacity`) with published caps
- [ ] automated success-rate guardrails tied to throughput campaign targets

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
- configurable circuit-breaker catalog for junk traffic control:
  - per-client reject-rate monitor over rolling window
  - configurable actions: `throttle`, `block`, escalation policy
  - simulator-level toggles (global + per-breaker on/off)
  - breaker telemetry in run summaries (`trips`, `active`, `released`)

### Tier 3: UX and Integrations

- simulator control room MVP:
  - local-only control API around allowlisted dev/simulator commands
  - run builder for stress/persona sessions
  - active run console with logs/status
  - report summary and run comparison views
  - scenario catalog backed by session config files
- docs-site scenario catalog pages generated from scenario files
- run tags/metadata for CI and benchmark dashboards
- optional stochastic mode (non-deterministic jitter) for long soak tests

Primary plan:
- [`docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](./SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)

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
- [x] move summary aggregation helpers into `internal/report` with focused tests

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

1. circuit-breaker feature (`reject-rate` throttle/block) with simulator toggles
2. runtime hot-path tuning based on lane-specific telemetry bottlenecks
3. remaining runner/package refactors (`runtime`, `actor`, `transport`)
4. realism nice-to-haves (regimes/liquidity/fault library expansion)

## Latest Locked Run (2026-05-27)

- reference doc: [`docs/THROUGHPUT_CAPACITY_BASELINE_2026-05-27.md`](./THROUGHPUT_CAPACITY_BASELINE_2026-05-27.md)
- scenario: `capacity-baseline`, `5m`, `rate=5000`, `workers=512`, clean reset before run
- post-tuning result:
  - throughput: `2694.15 rps`
  - accepted throughput: `2655.29 rps`
  - success rate: `98.56%`
  - trace checks: `50/50` pass
- post-fix quality note:
  - `REFERENCE_DATA_ERROR` rejects: `0` in the latest 5-minute run

## Next Effort: Abuse-Control Circuit Breakers

### Goals

- add configurable boundary guardrails for junk-traffic spikes without impacting normal simulator flows
- support global enable/disable and per-breaker toggle behavior
- preserve command capture integrity and throughput while blocking abusive client patterns

### Initial Scope

1. breaker policy model:
   - reject-rate window threshold
   - block duration
   - optional warning-only mode
2. boundary enforcement path:
   - identify client via `X-Client-Id`
   - evaluate breaker state before command processing
   - emit deterministic reject response + reason code when blocked
3. simulator controls:
   - enable/disable breaker globally
   - per-run tuning knobs for threshold/window/block duration
4. observability:
   - breaker hit counters
   - blocked client cardinality
   - clear log signal for tuning and incident debugging

### Exit Criteria

- breaker behavior is deterministic under replay
- simulator can toggle breakers without code changes
- no regression in baseline happy-path throughput profile

### Progress Checkpoint (2026-05-27)

- implemented reject-rate breaker with global/per-feature toggles and route scoping
- exposed runtime breaker telemetry snapshot endpoint: `GET /internal/boundary/abuse/stats`
- completed short non-tripping overhead A/B run:
  - reference: [`docs/ABUSE_BREAKER_COMPARISON_2026-05-27.md`](./ABUSE_BREAKER_COMPARISON_2026-05-27.md)
- completed intentional-trip campaign lane with breaker counters captured per lane artifact:
  - reference: [`docs/ABUSE_BREAKER_TRIP_LANE_2026-05-27.md`](./ABUSE_BREAKER_TRIP_LANE_2026-05-27.md)
- completed long-soak intentional-trip validation with release-cycle evidence (`releases > 0`):
  - reference: [`docs/ABUSE_BREAKER_LONG_SOAK_2026-05-27.md`](./ABUSE_BREAKER_LONG_SOAK_2026-05-27.md)

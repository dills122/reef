# Bot Arena Invite Preview Sprint

## Purpose

Deliver the first invite-only, fork-based Bot Arena preview from submission
through recorded game results after the Reef/Arena product boundary is proven.

This sprint combines three previously separate concerns:

- fork-safe admission and trusted provisioning
- run cutoff, roster locking, and eligibility timing
- bot persona and economic-policy calibration

[`REEF_BOT_ARENA_SEPARATION_SPRINT.md`](./REEF_BOT_ARENA_SEPARATION_SPRINT.md)
was a hard prerequisite. Its evidence was promoted on 2026-07-19; see
[`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](./REEF_BOT_ARENA_SEPARATION_PROMOTION.md).
Invite-preview code may now begin, subject to the release gates below.

The current release gate remains
[`BOT_ARENA_RELEASE_READINESS.md`](./BOT_ARENA_RELEASE_READINESS.md). This file
is the implementation plan that closes its invite-preview blockers.

## Sprint Length And Goal

Plan for **15 working days**. The scope crosses GitHub trust boundaries,
hosted identity/config, run orchestration, and economic calibration;
compressing it into a normal one-week feature sprint would trade away the E2E
evidence that is the point of the milestone.

Sprint goal:

> An invited user can submit a bot from a fork, pass untrusted checks, receive
> maintainer approval, provision and configure the bot before cutoff, merge an
> immutable reviewed version, enter a locked roster, complete recorded local
> and hosted games, and produce replayable scoring/economic evidence on top of
> the already-promoted Reef/Arena separation boundary.

## Locked Preview Decisions

- Submissions come from forks.
- Admission is invite-only during preview.
- A new fork PR starts as `pending_invite_review`, not rejected.
- The primary maintainer is the initial reviewer; no review-time SLA is
  promised during preview.
- Untrusted PR workflows never receive hosted API secrets, OpenBao
  credentials, or OIDC minting permission.
- Maintainer approval triggers a trusted provisioning step that uses
  base-branch code and immutable PR metadata and never executes fork code.
- The bot secret slice is provisioned and required config can be entered before
  merge, so the user does not lose a run solely to post-merge setup delay.
- Merge to `master` is the code-acceptance boundary.
- Post-merge registry sync rebuilds from `master`, verifies source/artifact
  hashes, and transitions the accepted version toward run eligibility.
- A bot that misses a cutoff rolls to the next run window. It is not rejected.
- `score-v1` is a preview scoring policy under calibration, not a permanent
  public compatibility promise.
- The SDK stays repository-local during the invite preview. Publishing it is a
  later decision informed by invited-author feedback.

## Submission And Run Window

All persisted timestamps use UTC. Operator UI and participant communication may
also show the configured venue timezone. Every run records both the absolute
timestamps and the policy version that produced them.

`T0` is the scheduled game start.

| Time | Gate | Required state |
| --- | --- | --- |
| Continuous | Submission intake | Fork PR may be opened at any time and enters `pending_invite_review`. No place in the next run is implied. |
| `T-72h` | Invite decision cutoff | Maintainer invite approval exists; immutable GitHub user identity, trust state, and ownership intent checks pass. |
| `T-72h` to `T-48h` | Provision/config window | Untrusted checks are green; trusted workflow provisions the bot slice; participant enters required config; config descriptor validation passes. |
| `T-48h` | Merge-readiness cutoff | Reviewed head SHA is unchanged; manifest, sandbox, provisioning, ownership, and config-readiness checks are green; human approval exists. |
| `T-48h` to `T-24h` | Merge and registry window | Maintainer merges; registry sync rebuilds from `master`; source/artifact hashes match; version reaches the preview-eligible lifecycle state. |
| `T-24h` | Roster lock | Run roster, bot versions, artifact hashes, config hashes, game mode, seed set, actor profiles, risk policy, scoring policy, and economic policy are snapshotted and immutable. |
| `T-2h` | Operational recheck | Images, secrets, config reads, service readiness, capacity, backups, projection health, and kill switches pass. No version or policy substitutions. |
| `T-30m` | Run instantiation | Run record and replay envelope are created; workers preflight artifacts and config; failed preflight removes the bot with an audited eligibility outcome. |
| `T0` | Game start | Only the locked eligible roster receives ticks. |
| `T+run duration` | Terminal drain | Commands reach terminal accounting; projections drain; scoring lock remains active. |
| `T+2h` target | Preview publication | Operator validates accounting, replay, enforcement, economic reconciliation, and score evidence before publishing the run. This is an operational target, not a participant SLA. |

### Making The Cut

A bot version makes the locked roster only when all of these facts are true at
`T-24h`:

- invitation is approved for the immutable GitHub user id
- PR was merged before roster assembly
- post-merge registry sync rebuilt the artifact from `master`
- merged source hash and artifact hash are recorded
- bot version is `approved` or the explicitly chosen preview-eligible status
- owner trust state is `trusted` and ownership is active
- bot and owner are not suspended, quarantined, banned, archived, or limited
- OpenBao slice exists
- every required config descriptor resolves and validates
- selected game mode permits the bot
- artifact/runtime version is supported by the runner pool
- risk-policy preflight passes
- capacity is available within the run's bot and worker budgets

No manual spreadsheet or memory-based exception can add a bot after roster
lock. The eligibility decision is a persisted, queryable fact with reason codes.

### Late Changes And Emergencies

- A push after maintainer approval invalidates the approval and all SHA-bound
  readiness checks.
- A bot merged after `T-24h` waits for the next run.
- Config changes after `T-24h` do not affect the locked run. The run uses the
  snapshotted config hash/value load associated with its replay envelope.
- Policy, seed, persona, and game-mode changes after `T-24h` create a new run
  version or invalidate the scheduled run; they never mutate the locked run.
- Operators may remove a bot for security, trust, config, or availability
  reasons until `T0`. Removal emits an audited eligibility event. No replacement
  version is inserted after `T-24h`.
- An emergency stop after `T0` marks the run non-ranked unless the scoring
  policy explicitly defines a deterministic recovery continuation.

## Required State Model

Submission/admission and bot-version lifecycle are related but not identical.
Do not overload `ArenaBotVersionStatus` to represent GitHub review workflow.

Recommended submission states:

```text
pending_invite_review
invite_approved
checks_passed
provisioned
config_ready
merge_ready
merged
registry_verified
eligible_for_roster
rolled_to_next_window
rejected
withdrawn
platform_blocked
```

Required persisted identifiers and timestamps:

- repository, PR number, fork owner, immutable GitHub user id, GitHub login
- head SHA approved by the maintainer
- invitation actor/reason/time
- untrusted-check run ids and results
- provisioning request/result and OpenBao path identifier
- config descriptor version, config hash, and last validated time
- merge SHA and registry-sync run id
- source hash and hosted artifact hash
- eligibility window id, cutoff evaluation time, result, and reason codes
- correlation id across GitHub, Admin API, OpenBao, registry, and run records

## Promoted Architecture And Optionality Baseline

The separation prerequisite is complete and remains a release invariant:

- `platform-runtime.jar` has no Arena classes, and `make build-reef-core` plus
  `make test-reef-core` enforce the Reef-only artifact and behavior.
- `services/arena-control-plane/` owns Arena routes, registry/admission stores,
  provisioning, admin use cases, and bot-version risk integration.
- Reef exposes product-neutral route and account-risk extension ports; it does
  not import Arena implementations.
- `compose.base.yml` plus `compose.local.yml` is Reef-only.
  `compose.arena.yml` explicitly adds the Arena artifact, datasource,
  migrations, and routes.
- Reef route-absence, Arena route-presence, Arena-storage failure isolation,
  matching-image equivalence, and P1 canonical equivalence are recorded in
  [`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](./REEF_BOT_ARENA_SEPARATION_PROMOTION.md).
- The Go matching engine remains unaware of Arena game and scoring behavior;
  bots continue to use Reef's public venue boundary.

The dependency direction is:

The required dependency direction is:

```text
arena-admin UI
arena control plane / runner / policies
        -> Reef public/admin contracts
        -> bot SDK and arena definitions

Reef platform runtime -> matching engine -> canonical venue/post-trade facts

Reef must have no compile-time dependency on Arena implementations.
```

Remaining work in this sprint is preview policy and evidence, not another
packaging extraction. A future network-service split may be justified by
deployment or scaling needs, but it is not required for the invite preview.
The first policy-resolution slice is now implemented: actor catalogs and
profiles reject unknown fields/parameters, the three named preview economic
policies are versioned fixtures, and the runner resolves canonical catalog,
economic-policy, profile, and composition hashes. Roster lock recomputes the
catalog and economic-policy hashes from canonical resolved content and rejects
caller-supplied version/hash pairs that do not match. Strict `score-v0` and
`score-v1` fixtures now drive runner scoring, resolved scoring content is
carried in reports, roster lock verifies its canonical hash, and accepted run
records lock the scoring/economic/envelope versions and hashes through terminal
score publication. Roster-to-run admission binding and economic reconciliation
remain tracked below.

## Persona And Economic Policy Modules

Keep three concepts independent and versioned:

### 1. Market Scenario

Owned by Reef/general scenario definitions:

- instruments, sessions, calendars, initial/reference market state
- deterministic exogenous events and seed
- venue and post-trade profiles

### 2. Arena Actor Composition

Owned by Arena definitions:

- competitor roster
- house market makers
- NPC/background flow
- benchmark actors
- actor profile versions, strict params, risk profiles, latency buckets, and
  seed salts

Current `actor-profiles.v1.json` and `bot-catalog.v1.json` are a good start, but
the sprint must add schema validation, resolved hashes, and run persistence.

### 3. Arena Economic Policy

Owned by a new versioned Arena policy definition:

- competitor and house starting capital
- position, notional, leverage, and credit limits
- fee and rebate schedules
- faucets, sinks, neutral transfers, carry, and penalties
- margin/liquidation behavior where enabled
- house liquidity budgets and intervention policy
- mark-to-market/finality rules used by scoring
- scoring-policy reference and active scoring lock

The policy belongs in Arena-owned definitions and persistence. Reef remains the
authority for venue trades, settlement, and ledger facts; Arena interprets
those facts under a locked game policy.

Recommended definition layout:

```text
packages/scenario-definitions/arena/
  modes/
  actors/
  economics/
  scoring/
  risk/
  schemas/
```

Each resolved run envelope stores version ids plus canonical content hashes, not
only filenames.

## Sprint Workstreams

## Workstream A: Fork-Safe Invite Admission

Deliverables:

- accept fork PRs through untrusted manifest/sandbox checks
- persist `pending_invite_review` rather than publishing a terminal denial
- maintainer-only invite action using a protected environment, manual workflow,
  or equally explicit trusted control
- trusted workflow checks out base-branch scripts only
- SHA-bound provisioning and readiness status
- idempotent OpenBao provisioning with ownership/limit/trust checks
- clear PR feedback for user-fixable, maintainer-fixable, and platform-fixable
  outcomes

Tests:

- unknown fork remains pending and cannot provision
- invited fork provisions without executing fork code in the trusted job
- non-maintainer cannot approve an invitation
- new commits invalidate approval/readiness
- retries do not create duplicate identities or slices
- banned/limited users and ownership conflicts fail closed

## Workstream B: Cutoff, Roster, And Run Admission

Implementation status (2026-07-20): the first durable operator slice is in
place. Arena owns the versioned `T-72h`/`T-48h`/`T-24h`/`T-2h`/`T-30m`
window, stable eligibility outcomes and reason codes, deterministic
capacity-priority ordering, immutable hashed roster snapshots, Postgres
migration/store validation, and authenticated admin routes for scheduling,
evaluation, deterministic preview, lock, removal, and readback. The operator
surface at `/admin/admission` shows included, capacity-overflow, rolled, and
excluded decisions with explicit priorities. Exact cutoffs are inclusive and
late readiness rolls to the next window; bot/owner restrictions are hard
exclusions. Emergency removal is an immutable audit overlay between roster lock
and `T0`: it changes the effective roster without mutating the snapshot or
adding a replacement. Roster lock now also verifies canonical actor-catalog and
economic-policy content before accepting their version/hash references.
Remaining work here is recorded hosted/external-account evidence.

Deliverables:

- versioned `ArenaAdmissionWindowPolicy`
- scheduled run window record with all cutoff timestamps
- eligibility evaluator with stable reason codes
- immutable roster snapshot and hash
- config snapshot/hash and artifact/source hashes in the run envelope
- operator preview showing included, capacity-overflow, rolled, and excluded
  bots before lock (implemented)
- audited emergency removal without late replacement (implemented)

Tests:

- exact-boundary tests at one millisecond before/at/after each cutoff
- timezone/DST tests use UTC persistence and configured display timezone
- merge after cutoff rolls forward
- config/version/policy mutation after lock cannot change the run
- deterministic roster hash across replay
- capacity overflow uses explicit deterministic priority, never map iteration or
  arrival-time accident

## Workstream C: Separation Regression Gate

Prerequisite evidence from
[`REEF_BOT_ARENA_SEPARATION_SPRINT.md`](./REEF_BOT_ARENA_SEPARATION_SPRINT.md):

- `make test-reef-core`
- `make dev-up-reef` and `make dev-smoke-reef`
- Reef-only Compose profile with no Arena DB, UI, runner, routes, env
  requirements, or volumes
- separate Arena build module/artifact or source-set boundary
- import/dependency guard tests
- Arena overlay/profile that restores the complete game stack

This sprint reruns those gates after invite, roster, persona, and policy changes;
it does not reopen the module extraction unless a regression is found.

Acceptance:

- Reef-only build/test succeeds without installing Bot SDK dependencies
- Reef-only stack starts with no `arena-postgres` container
- Arena URLs are absent, not merely `503`
- submit/match/cancel/modify, scenarios, replay, settlement, and core reads pass
- matching-engine binary/image is byte-identical between Reef-only and
  Arena-enabled deployments for the same commit
- enabling Arena changes adapters/deployment only, not venue semantics

## Workstream D: Persona And Policy Definitions

Implementation status (2026-07-20): strict reusable actor/economic resolvers,
canonical SHA-256 hashing, version/reference consistency checks, the
`preview-zero-fee-v1`, `preview-balanced-fee-v1`, and
`preview-liquidity-subsidy-v1` fixtures, runner report/envelope hashes, and
roster-lock hash verification are implemented. Strict scoring-policy
definition/reference resolution, resolved report artifacts, persisted run
policy locks, result-to-run hash verification, terminal result immutability,
and leaderboard lock filtering are also implemented. The next slice is binding
run admission to the accepted roster snapshot and competition/house ledger
reconciliation.

Deliverables:

- strict schemas for actor catalog, actor profiles, economic policy, scoring
  policy reference, and run composition
- versioned preview economic policies:
  - `preview-zero-fee-v1` baseline
  - `preview-balanced-fee-v1` comparison
  - `preview-liquidity-subsidy-v1` comparison
- explicit cash source/sink and house/competition ledger reconciliation
- resolved policy/profile hashes persisted on run records and reports
- scoring lock prevents policy mutation from run acceptance through score
  publication

Initial persona matrix:

- house: passive benchmark, tight blue-chip maker, inventory-stressed maker
- NPC: benign noise, balanced institutional flow, aggressive retail flow,
  toxic momentum, liquidity shock
- competitor: passive maker, aggressive taker, multi-symbol momentum,
  lifecycle-safe refreshing maker, intentionally invalid/abusive negative bot

## Workstream E: Recorded Calibration Campaign

Use fixed named run suites so evidence is comparable.

### Gate 1: Fast Determinism

- local, unpaced
- one mode, one seed, baseline persona/policy mix
- run twice from reset
- require identical roster, policy/profile hashes, actions, terminal accounting,
  economic reconciliation, and score output

### Gate 2: Local Paced E2E

- `5m` per run
- three seeds
- baseline economic policy
- at least five eligible competitors plus house/NPC/benchmark actors
- positive and negative/enforcement variants

### Gate 3: Persona Calibration Matrix

- `5m` local or short hosted runs
- three NPC regimes x three seeds
- fixed bot roster and baseline economic policy
- compare spread, depth, volatility, fill rate, slippage, inventory, turnover,
  rejects, and rank stability

### Gate 4: Economic Policy Matrix

- three economic policy versions x three seeds
- fixed actor mix and bot versions
- public `score-v1` plus shadow component breakdown
- compare wealth concentration, turnover, fees/rebates, house losses, competitor
  drawdown, source/sink reconciliation, and rank reversals

### Gate 5: Hosted Confidence

- three `15m` runs on the promoted hosted profile
- different seeds, same locked roster/policy for comparability
- zero command accounting gap, projection drain, no unexplained worker loss,
  replay/checksum pass, economic reconciliation pass

### Gate 6: Invite Preview Rehearsal

- external test account using a real fork
- complete submission, invite, provisioning, config, merge, registry, cutoff,
  roster, run, result publication, quarantine, recovery, and next-version update
- one `30m` rehearsal followed by a second clean `30m` run
- record operator time and every manual step

## Evidence Bundle

Every promoted run must retain:

- run window and cutoff policy
- admission evaluations and locked roster hash
- source/artifact/config hashes
- scenario, seed, actor profile, risk, scoring, and economic policy versions and
  resolved hashes
- command accounting and terminal outcomes
- worker timing/resource/isolation evidence
- venue replay/checksum and projection watermarks
- fills, positions, cash, equity, settlement/finality state
- fees, rebates, penalties, faucets, sinks, and reconciliation hash
- market-quality metrics and persona attribution
- public score, shadow score components, rank stability inputs
- enforcement and operator decisions
- environment/image versions and logs required to reproduce the result

Training/calibration exports must exclude secrets, raw private config, tokens,
and other bots' private observations. A bot may receive its own private state;
the operator dataset can be richer, but access and retention must be explicit.

## Day Plan

### Days 1-2: Contracts And Boundary Lock

- ratify cutoff policy, states, reason codes, and invite control
- add admission/economic/persona schemas and fixtures
- verify the promoted Reef-core/Arena dependency rules remain enforced
- write failing contract tests first

### Days 3-5: Fork Admission And Provisioning

- accept/persist fork pending state
- implement maintainer invite approval
- implement trusted SHA-bound provisioning and config readiness
- add branch-protection/protected-environment runbook
- run negative trust-boundary tests

### Days 6-8: Roster And Separation Regression

- verify and extend the implemented window/eligibility/roster snapshot through
  hosted evidence and the operator preview
- run Reef-only artifact, Compose, route, and smoke gates
- prove new invite/roster code remains Arena-owned
- run Arena overlay compatibility and canonical Reef-fact comparison

### Days 9-10: Policy And Persona Resolution

- implement strict resolvers and canonical hashes
- persist resolved policy/profile/config references
- bind accepted run policy locks to the immutable roster snapshot and implement
  economic reconciliation
- update reports/comparison tooling

### Days 11-12: Local Evidence

- determinism gate
- paced positive/negative gates
- persona matrix
- economic-policy matrix
- fix only evidence-backed failures; do not tune around one seed

### Days 13-14: Hosted Evidence

- three hosted confidence runs
- validate replay, drain, reconciliation, scoring, and rank stability
- run full external-test-account admission rehearsal through roster lock

### Day 15: Preview Rehearsal And Decision

- run first `30m` rehearsal
- quarantine/recover one bot and submit an updated version
- run the second `30m` rehearsal if the first failure is understood and fixed
- publish readiness report and go/no-go decision for invited users

## Definition Of Done

- real fork submission completes the approved invite flow without privileged
  execution of fork code
- pre-merge provisioning/config prevents avoidable post-merge run misses
- cutoff and roster behavior is deterministic, persisted, and audited
- two clean `30m` rehearsals exist with complete evidence bundles
- persona and economic-policy matrices produce comparable reports across seeds
- score-v1 remains unchanged by calibration tooling unless an explicit new
  policy version is selected
- monetary reconciliation gap is zero for every promoted run
- Reef-only build and stack pass with Arena absent
- Arena-enabled stack passes without changing Reef venue semantics
- operator runbook documents every manual step and explicitly promises no
  invite-review turnaround time
- public copy says invite-only preview and explains that missing a cutoff rolls
  the bot to the next window

## Go/No-Go Rules

No-go for invited users if any of these remain:

- fork code executes in a trusted workflow
- branch protection or invite authorization can be bypassed
- reviewed SHA, merged SHA, registry hash, and run artifact cannot be linked
- a late mutation changes a locked roster or policy
- accepted-command, replay, projection, or monetary reconciliation gaps remain
- Reef core still requires Arena infrastructure to boot
- a promoted run cannot be reproduced from its retained envelope
- operator cannot quarantine a bot before the next tick/run admission

## Decisions Deferred Beyond The Sprint

- public SDK package publication and compatibility promise
- guaranteed submission-review SLA
- open/non-invite submission volume
- permanent weekly game day and public season calendar
- final score-v1 compatibility lock
- maker-rebate and leverage defaults beyond the preview comparison policies
- dedicated Arena network service versus separate build module in the same
  deployable runtime

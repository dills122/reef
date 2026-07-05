# Bot Arena Intake — Current State & Gap Analysis

Status: draft, 2026-07-05. Scope: the bot registration/submission intake pipeline ("Bot Arena" control plane), not the general order-command boundary (which is separately hardened — see below).

## 1. Current shape

Intake pipeline for bots wanting to trade in the simulator:

`register bot → register version → qualification report → operator transition (Draft→Submitted→ChecksPassed→Approved→Active, or Suspended/Quarantined/Banned/Archived) → runtime config (secret refs) → run registration (Approved/Active only) → run results → leaderboard`

Key files:
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/arena/ArenaControlPlaneService.kt` — data model, state machine, validation (`require()` checks)
- `InMemoryArenaBotRegistryStore.kt` / `PostgresArenaBotRegistryStore.kt` — storage, isolated JDBC datasource from trading hot path
- `ArenaBotVersionRiskCheck.kt` — rejects commands from unapproved bot versions
- `PlatformHttpServer.kt:2131-2260` — `/internal/admin/arena/*` routes, gated by `PLATFORM_ARENA_ADMIN_ENABLED`
- `packages/bot-sdk/src/runtime-config.ts` — SDK-side preflight
- `docs/BOT_ARENA_PLAN.md` — plan of record

Trigger: internal admin HTTP endpoints only (no public submission surface yet).

## 2. What already exists at the engine/boundary level (do not re-build)

These live in `ExternalApiBoundary.kt` and apply to **all** order flow — human and bot — not arena-specific:

| Control | Implementation | Notes |
|---|---|---|
| Pre-trade risk / position check | `AccountRiskCheck` (`StaticAccountRiskCheck`, `PostgresAccountRiskCheck`) | Runs pre-acceptance |
| Price-band / fat-finger guard | `InstrumentPriceCollar*` + `PostgresInstrumentPriceCollarStore` | Submit-order pre-acceptance gate |
| Rate limiting | `RateLimitHook` / `FixedWindowRateLimitHook` / `InMemoryRateLimitStore` | Per-client scope |
| Kill switch / circuit breaker | `CommandCircuitBreakerCheck` + `PostgresCommandCircuitBreakerStore` | Scoped global / venue-session / instrument; hard pre-acceptance gate |

Pipeline order (`docs/DECISIONS.md:29-32`): auth → idempotency → rate limit → validation → account/bot risk pre-check → circuit breaker → price collar. All pre-acceptance, all audited.

**Takeaway: baseline safety is not missing.** The gap is a missing arena-specific overlay on top of these general controls (see below).

## 3. Gaps — two tiers, not one

`docs/BOT_ARENA_PLAN.md` itself splits into an accepted tier and an explicitly-not-yet-decided tier (line 15, line 17-19). Treat them differently:

**Tier A — agreed direction, now ratified as [D-045](DECISIONS.md) ("Bot Arena Intake Direction"):** SDK/protocol/sandbox architecture. Not a gap, just needed promoting from plan doc to decision record — done.

**Tier B — "Deferred Second Review" (`BOT_ARENA_PLAN.md:17-49`):** explicitly *not an implementation commitment* until a formal second review happens. Safety limits, fairness rules, secret handling rules, failure handling, onboarding/KYC, approval-lifecycle detail all live here. Building code against these before the review risks rework once the review lands.

Concrete items, with tier and Rollout Plan phase (`BOT_ARENA_PLAN.md:619-667`) noted:

1. **Per-bot abuse controls** (order-rate cap, cancel/replace rate, max-open-orders, self-trade prevention) — Tier B, `BOT_ARENA_PLAN.md:320-336`. Maps to Rollout Plan Phase 2 (Sandbox Execution)/Phase 5 (User Submissions). Current implementation is early Phase 1 (registry only, no sandbox, no public bot traffic yet) — no adversarial traffic exists today, so this is not yet a live exposure, just a prerequisite before Phase 5.
2. **Onboarding/KYC-style gate** — Tier B, `BOT_ARENA_PLAN.md:27`. Phase 5 concern. Note: submission-flow identity/provisioning (who gets an OpenBao identity, when) is now resolved — see "Resolved Slice: Bot Submission Workflow And OpenBao Provisioning" below — but that's the provisioning mechanism, not a KYC/eligibility gate; KYC-style admission criteria are still undecided.
3. **Fairness rules** — Tier B, `BOT_ARENA_PLAN.md:43`. Unresolved by design, not an oversight.
4. **Failure handling** (bot crash, runner timeout, duplicate submission) — Tier B, `BOT_ARENA_PLAN.md:48`.
5. **Secret rotation/audit** — **partially resolved.** Provisioning mechanism (PR-based, branch enum `bots/add|update|remove/<name>`, registry-diff detection, blocking pipeline stages: bot validity → static/security scan → sandboxed test run → OpenBao provisioning, slice path `secret/arena/<submitter-identity>/<bot-id>` with no version segment) is now written up in `docs/BOT_ARENA_PLAN.md` ("Resolved Slice: Bot Submission Workflow And OpenBao Provisioning", added 2026-07-05). Still open: secret *rotation* policy itself, and the preflight wiring of the platform-owned OpenBao provider into runner preflight (`BOT_ARENA_PLAN.md:87`) — that's runtime consumption of the secret, separate from the provisioning-at-submission-time flow just resolved.
6. **Result ingestion gap** — explicit remaining-work item, not Tier B (`BOT_ARENA_PLAN.md:88`): hosted simulation/test-bot run summaries not yet ingested into `arena.run_bot_results`. Concrete, scoped, no dependency on the second review.
7. **Postgres schema drift risk** — explicit remaining-work item, not Tier B (`BOT_ARENA_PLAN.md:85`): keep `PostgresArenaBotRegistryStore` validation in schema-placement CI as arena schema evolves. Ongoing hygiene.
8. **Arena decisions not promoted** — resolved by D-045 (Tier A only; Tier B intentionally excluded pending review).

## 4. Future-state direction

See [D-045](DECISIONS.md) for the ratified Tier A direction (TypeScript SDK, protobuf runtime contract, gRPC sandbox boundary, hot-path DB isolation, stream-ack ingress target, Rollout Plan Phase 5 gating on Phases 1-4).

## 5. Recommended priority order

1. **Run the Tier B second review** (`BOT_ARENA_PLAN.md:17-49`) — a design/discussion task, not code. Blocks #2 below from being built against a stable spec.
2. **Result ingestion into `arena.run_bot_results`** (#6) — concrete, scoped, no Tier B dependency. Good near-term code slice.
3. **Postgres schema-placement CI coverage** (#7) — concrete, scoped, no Tier B dependency. Good near-term code slice.
4. **OpenBao preflight wiring** (#5, non-rotation half) — needed before Active bots handle real secrets at scale; independent of Tier B review.
5. **Per-bot abuse controls, failure handling, onboarding/KYC, fairness rules** (#1-4) — hold until Tier B review lands a spec, to avoid building against an unstable target. Not urgent today since no adversarial/public bot traffic exists yet (Phase 1 only).
6. **Bot-submission PR pipeline** — scaffolded: `.github/workflows/bot-submission.yml` plus `scripts/dev/bot-submission-validate.mjs`, `bot-submission-registry-diff.mjs`, `bot-submission-provision-openbao.mjs` (added 2026-07-05). Stages 1-3 are real and run in separate jobs (manifest validation; combined security-scan/sandboxed-test via `bot-sdk-test-bot.mjs`); these may use an ephemeral `make dev-up` stack since they need no persistent state. Stage 4 (registry-diff + OpenBao provisioning) is corrected to call the real, always-on hosted admin API (`ARENA_ADMIN_API_URL`/`ARENA_ADMIN_API_TOKEN` secrets) instead — an earlier draft of this job spun up its own ephemeral `dev-up` stack, which would have made registry-diff meaningless (empty throwaway registry, every bot looks "new") and any "provisioned" secret non-persistent. Its OpenBao step remains a **dry-run stub** — no OpenBao client integration exists yet, so it intentionally fails outside `BOT_SUBMISSION_OPENBAO_DRY_RUN=1`. Real infra context (`infra/hetzner-core/`, PR #45/`aa70ed4`, **merged to master 2026-07-05**): already bootstraps OpenBao with **AppRole** auth for `platform-runtime`/`simulator` runtime reads of `secret/data/bots/*` — that stays unchanged, and CI never runs Kotlin/Gradle directly (it only calls the hosted HTTP API; the Kotlin client and GitHub OIDC exchange run server-side). Remaining work: (a) expose a narrow Caddy route (registry read + a new `openbao-provision` route) through the currently loopback-only admin API, bearer-token-gated, (b) add a second `auth/jwt` backend alongside the existing AppRole backend for GitHub OIDC-based CI provisioning, scoped to a narrow policy on `secret/data/bots/*` only (path convention: `secret/bots/<submitter-identity>/<bot-id>`, nested under the existing wildcard), (c) build the OpenBao HTTP client (none exists in Kotlin or TS anywhere in the repo) as a platform-runtime application module exposed via a new admin route, and wire the CI script to call it in place of the stub, (d) configure branch protection on `master`/`main` to require the three job status checks plus a human reviewer — a manual GitHub repo-settings change, not something committed code can express.
7. **Backbone/run-plane infra split** — [D-046](DECISIONS.md), finalized 2026-07-05 against the merged `infra/hetzner-core/` (PR #45/`aa70ed4`). Gap analysis: backbone got OpenBao, Admin API (`platform-runtime`), and Caddy (all done, `infra/hetzner-core/server/docker-compose.yml`); everything else is still open work:
   - split the single shared Postgres instance's `admin`/`analytics` schemas (`infra/hetzner-core/server/postgres/init/01-create-dbs.sh`) into two separate Postgres containers on the same backbone droplet
   - build the Analytics microservice (API + DB) — currently just a schema name, no service exists
   - rebuild `infra/simulation-runner/` to mirror the backbone's OpenTofu+compose+deploy-script pattern (new `infra/simulation-runner/tofu/` on the DO provider, its own `docker-compose.yml`, a deploy script shaped like `scripts/deploy/hetzner-core-tofu.mjs`), replacing today's ad-hoc reuse of `scripts/dev/do-benchmark-host.sh` inside `scripts/deploy/simulation-run.mjs`
   - build the export/cleanup service, running on the ephemeral DO simulation droplet as the last step before teardown, replacing the raw `rsync` in `simulation-run.mjs`'s `pushArtifacts()`
   - wire Cloudflare R2 for compressed simulation debug-data storage, reusing the credentials already set up for Postgres/OpenBao backup dumps

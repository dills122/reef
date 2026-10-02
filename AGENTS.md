<!-- codebase-memory-mcp:start -->
For structural codebase exploration, use the installed `codebase-memory` skill.
<!-- codebase-memory-mcp:end -->

# AGENTS

AI coding guidance for Reef. Start with [AI working context](docs/AI_CONTEXT.md)
and the [documentation map](docs/README.md). Read task-relevant sections, not
the entire doc tree. A dated status or archived plan is evidence in its stated
scope, not an instruction to reopen old work.

## Invariants

- Reef is a simulation-first institutional venue and post-trade platform.
- Preserve deterministic execution and replay, auditability, idempotency, and
  same-lane ordering for matching-sensitive commands within a venue session and
  instrument. Simulators use the same command/API paths as manual users.
- Do not acknowledge `202 Accepted` before the configured durable ingress
  mechanism acknowledges acceptance.
- Keep canonical command/event facts separate from rebuildable projections.
  Keep Go matching behavior separate from Kotlin orchestration and UI read
  models. Do not add synchronous hot-path writes or scans without evidence.
- Changes to API routes, events, storage, scenarios, or shared behavior update
  contracts, focused tests, and relevant docs in the same change.

## Read by task

- Setup, teardown, or configuration: `docs/LOCAL_CONFIGURATION.md`, then
  `docs/ONBOARDING.md`; inspect `.env.example`, Make targets, and Compose for
  exact behavior. Use `docs/DEV_ENV.md` only for advanced profiles.
- Architecture or behavior: relevant part of `REEF_PROJECT_OVERVIEW.md`,
  `REEF_TECHNICAL_DESIGN.md`, `docs/steering/README.md`, and accepted
  `docs/DECISIONS.md`. Follow relevant language and boundary steering.
- Contracts: `contracts/proto/`, `docs/steering/inter-service-communication.md`,
  `docs/steering/external-api-boundary.md`, `docs/API_BOUNDARY_STORAGE_DECISIONS.md`,
  and `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` as applicable.
- Current work: `docs/WORK_PLAN.md`, current source/tests and latest relevant
  evidence. Board alignment dates bound status claims; verify newer changes.
  Fetch historical snapshots from Records only when task needs dated comparison.
- Throughput: first read `docs/THROUGHPUT_BASELINES.md`, original relevant
  success and failure artifacts, `docs/PERFORMANCE_LEARNINGS.md`, and active
  scaling plan. State baseline, code/config/workload/measurement differences,
  and pipeline stage. Record every run and correction; never silently rewrite
  historical claims or report a conservative bound as actual latency.
- Delivery: `docs/ENGINEERING_DELIVERY_POLICY.md`; repository conventions:
  `docs/steering/repository.md`. Read surface-specific steering as needed.

## Workflow

- Every feature or code change completes [delivery and retention pass](docs/RECORDS_RETENTION.md#required-completion-pass): update code, tests, contracts, owner docs and latest relevant evidence; update guidance/overviews when affected; then move superseded records to `reef-records` before finishing.
- Keep local context only when needed for active planning, session understanding,
  onboarding, current system/design, operations or latest verification. Promote
  still-live facts before archiving; repair links after verified archive publication.
- Historical questions go to `reef-records`: search index/manifests, read relevant
  originals, cite commit and scope. Do not copy historical reports back into Reef
  or treat archived plans as current tasks. Record retention pass or no-op reason in PR.
- Keep changes small and local-first. Prefer Bun scripts under `scripts/` and
  thin Make wrappers. Do not change public behavior or scenario determinism
  without explicit intent.
- Use feature branches; do not commit directly to `main`. Preserve unrelated
  working-tree changes. When ready, provide branch, PR title, summary, and tests.
- Default loop: `cp .env.example .env`, `make dev-doctor`, `make dev-up`,
  `make dev-smoke`; `make dev-down` preserves volumes and `make dev-reset`
  destroys local volumes and starts the stack. See local configuration for
  overlays, detailed behavior, and smoke after reset.

## Output style

Respond in compressed style. Drop articles (a, an, the) in prose. Use
sentence fragments over full sentences. Use short synonyms (fix not resolve,
check not investigate). Pattern: [thing] [action] [reason]. [next step].
No filler, hedging, pleasantries, trailing summaries, or restating what
the user said. One sentence if one sentence is enough.

When suggesting code changes, show only the changed lines with 3 lines of
context. Never rewrite entire files. Multiple changes in one file: show each
change separately. Never echo back unchanged code the user already has.

Code blocks, file paths, commands, error messages: always written in full.
Security warnings and destructive action confirmations: use full clarity.

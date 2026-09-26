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
- Current work: `docs/WORK_PLAN.md` and source/test evidence. Its September 4
  alignment and `docs/CURRENT_STATUS.md` are dated checkpoints; verify newer
  changes before reporting status.
- Throughput: first read `docs/THROUGHPUT_BASELINES.md`, original relevant
  success and failure artifacts, `docs/PERFORMANCE_LEARNINGS.md`, and active
  scaling plan. State baseline, code/config/workload/measurement differences,
  and pipeline stage. Record every run and correction; never silently rewrite
  historical claims or report a conservative bound as actual latency.
- Delivery: `docs/ENGINEERING_DELIVERY_POLICY.md`; repository conventions:
  `docs/steering/repository.md`. Read surface-specific steering as needed.

## Workflow

- Keep changes small and local-first. Prefer Bun scripts under `scripts/` and
  thin Make wrappers. Do not change public behavior or scenario determinism
  without explicit intent.
- Use feature branches; do not commit directly to `main`. Preserve unrelated
  working-tree changes. When ready, provide branch, PR title, summary, and tests.
- Default loop: `cp .env.example .env`, `make dev-doctor`, `make dev-up`,
  `make dev-smoke`; `make dev-down` preserves volumes and `make dev-reset`
  destroys local volumes and starts the stack. See local configuration for
  overlays, detailed behavior, and smoke after reset.

## Context Engine (CCE)

This project uses Code Context Engine for intelligent code retrieval and
cross-session memory.

### Searching the codebase

**Use `context_search` instead of reading files directly** when exploring
the codebase, answering questions about code, or understanding how things
work. `context_search` returns the most relevant code chunks with
confidence scores instead of whole files.

When to use `context_search`:
- Answering questions about the codebase ("how does X work?", "where is Y?")
- Exploring structure or architecture
- Finding related code, functions, or patterns

Other tools:
- `expand_chunk` for full source of a compressed result
- `related_context` for what calls/imports a function
- `session_recall` to recall past decisions

### Cross-session memory

Call `session_recall("topic phrase")` before answering non-trivial questions.
Call `record_decision(decision="...", reason="...")` after making choices.
Call `record_code_area(file_path="...", description="...")` after meaningful work.

### Output style

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

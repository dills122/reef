# Reef Repository Conventions

## Purpose

This file captures repo-level conventions that apply regardless of language.

For product and architecture priorities that should guide every change, read [`repository-scope-and-priorities.md`](./repository-scope-and-priorities.md) alongside this file.

## Conventions

- use `apps/` for user-facing applications
- use `services/` for backend services and engines
- use `contracts/` for shared schemas and inter-service contracts
- use `packages/` for reusable internal libraries or shared models
- use `docs/` for roadmap, steering, architecture notes, and project documentation
- use `scripts/` for operational/dev automation entrypoints

## Ownership Boundaries

- `apps/docs-site/` owns public/static documentation and should not contain runtime logic.
- `services/platform-runtime/` owns Reef API boundaries, workflow orchestration, durable command intake, persistence, read models, projections, product-neutral extension ports, and generic admin behavior.
- `services/arena-control-plane/` owns optional Arena routes, registry/admission persistence, provisioning, Arena admin use cases, and the bot-version risk extension. It may depend on Reef contracts; Reef must not depend on Arena implementations.
- `services/matching-engine/` owns deterministic matching and execution behavior, not platform workflow orchestration or persistence.
- `services/simulator/` owns deterministic scenarios, seeded actors, replay, traffic generation, stress/load evidence, and trace validation.
- `contracts/proto/` owns versionable inter-service contracts.
- `packages/scenario-definitions/` owns reusable scenario inputs, seeds, clocks, actors, instruments, and correlation inputs.
- `packages/bot-sdk/` owns the first-party bot authoring contract (`ReefBotV1`), examples, and fixtures; it does not create runtime routes, persistence tables, or direct service clients.
- `scripts/` owns setup, reset, smoke, stress, replay, diagnostics, and performance comparison workflows.
- `infra/` owns hosted and remote-run infrastructure (`hetzner-core/`, `simulation-runner/`, `local-kube/`, `do-benchmark/`), separate from the local Compose dev stack.

## Scripting Standard

- prefer `bun`-executed JavaScript/TypeScript scripts for repository automation
- keep `make` targets as thin wrappers around versioned scripts in `scripts/`
- prefer grouped local CLIs such as `scripts/dev/reef-dev.mjs` for stack, stress, and local link profiles instead of adding one-off wrapper scripts
- avoid adding new shell-heavy automation unless there is a clear platform/runtime reason
- when scripts call external tools (for example Docker/Go), keep argument handling explicit and cross-platform friendly
- performance scripts should report attempted, durably accepted, processed, and projected throughput separately when those stages apply
- stress/replay scripts should preserve comparable evidence artifacts across runs

## Code Health

- treat file/class size as a smell signal, not a hard rule: a class mixing more than 2-3 unrelated concerns (auth, routing, business JSON marshaling, composition wiring) should split along concern boundaries even if line count alone looks tolerable
- once an HTTP/API entrypoint file accumulates route handlers for more than one bounded context, extract per-context route modules instead of adding more private methods to the entrypoint class (see `docs/steering/kotlin.md` for the Kotlin route-module shape)
- composition-root wiring (default service construction, env-var bootstrap) belongs in its own file, separate from request handling
- extract a shared helper the second time the same small pattern (env-var lookup, DB bootstrap, retry loop) is copy-pasted, rather than letting copies drift — a duplicated helper that already disagrees on edge-case behavior (e.g. how `0` is treated) is worse than no shared helper
- when extracting, check for an existing local pattern first (grep sibling files in the same package/directory) before inventing a new structure

## Naming

- name modules by business role, not by technology novelty
- prefer `platform-runtime` over generic names like `api-server`
- prefer `matching-engine` over ambiguous names like `core`
- prefer bounded-context terminology where it improves clarity

## Documentation Expectations

- significant architectural decisions should be reflected in `docs/`
- steering documents should change when the intended direction changes materially
- README content should describe the current project shape, not the original prototype only
- throughput-sensitive changes should update benchmark notes or link evidence when they change accepted/processed/projected capacity, latency, write amplification, or backlog behavior
- behavior changes that affect API routes, event semantics, storage formats, scenario determinism, partitioning, or command acceptance semantics must update the relevant contract and steering docs

## Safe Change Defaults

- keep feature changes scoped to one bounded context where possible
- add focused tests for behavior changes
- prefer projection/read-model improvements that do not alter canonical semantics
- preserve public `/api/v1` direction, idempotency behavior, and backpressure semantics unless the change explicitly targets those contracts
- avoid adding synchronous writes, table scans, or read-model updates to command hot paths without a clear reason and, where relevant, benchmark evidence

## Incremental Adoption

The current repo predates this structure.
When reshaping it, preserve useful history where practical, but do not force old prototype layouts to dictate the new platform architecture.

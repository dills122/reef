# Reef Repository Conventions

## Purpose

This file captures repo-level conventions that apply regardless of language.

## Conventions

- use `apps/` for user-facing applications
- use `services/` for backend services and engines
- use `contracts/` for shared schemas and inter-service contracts
- use `packages/` for reusable internal libraries or shared models
- use `docs/` for roadmap, steering, architecture notes, and project documentation
- use `scripts/` for operational/dev automation entrypoints

## Scripting Standard

- prefer `bun`-executed JavaScript/TypeScript scripts for repository automation
- keep `make` targets as thin wrappers around versioned scripts in `scripts/`
- avoid adding new shell-heavy automation unless there is a clear platform/runtime reason
- when scripts call external tools (for example Docker/Go), keep argument handling explicit and cross-platform friendly

## Naming

- name modules by business role, not by technology novelty
- prefer `platform-runtime` over generic names like `api-server`
- prefer `matching-engine` over ambiguous names like `core`
- prefer bounded-context terminology where it improves clarity

## Documentation Expectations

- significant architectural decisions should be reflected in `docs/`
- steering documents should change when the intended direction changes materially
- README content should describe the current project shape, not the original prototype only

## Incremental Adoption

The current repo predates this structure.
When reshaping it, preserve useful history where practical, but do not force old prototype layouts to dictate the new platform architecture.

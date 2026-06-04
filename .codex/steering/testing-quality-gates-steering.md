# Testing And Quality Gates

Testing should protect behavior, contracts, and integration boundaries.

## Default Expectations

- Add or update focused tests for behavior changes.
- Cover edge cases for parsing, validation, permissions, persistence, and external integrations.
- Keep test fixtures small and explicit.
- Prefer deterministic tests over timing-sensitive assertions.

## Before Finishing Work

Run the smallest reliable command that validates the changed area:

- Repository tests: `make test`
- Go matching engine tests: `make test-go`
- Kotlin platform runtime tests: `make test-platform-runtime`
- Proto compatibility check: `make check-proto-additive`
- Local smoke test: `make dev-smoke`
- Benchmark guardrails when touching performance-sensitive paths: `make bench-matching-engine-check` or `make bench-platform-runtime-check`

If a command cannot run locally, document why and what risk remains.

## Quality Gates

- No known failing tests introduced by the change.
- No unrelated formatting churn.
- Public contracts updated when behavior changes.
- Docs updated for setup, command, or workflow changes.

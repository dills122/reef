# Calcify finite P3 O2 — verification record, October 11, 2026

Scope: independent re-verification of Codex's uncommitted O2 work (preserved at
`wip/calcify-o2-codex-snapshot-2026-10-11`, base `5730b6be1`) after cherry-pick onto
`master` `b9a8756e8` (PR485 merged). Branch `claude/calcify-o2-finish`. Host: macOS 26.6.2
arm64, Docker 29.7.2, Go 1.26.9, Gradle 9.8.0 / JDK 21 toolchain. Nothing here is an
independent review verdict: O2 review count stays 1 of 3 (instance 1 "Not ready").
No capacity, financial-authority or production-adoption claim follows.

## Review finding status

| Finding | State | Evidence |
| --- | --- | --- |
| F1 / G9 full managed-state transport | Transport proven for the declared cap | `FiniteG9BrokerTransportTest`, below |
| F2 finite matcher compression pin | Author fix present; focused tests and full module pass | `calcify_finite_credentials.go:118` sets `CompressionNone`; `TestKafkaConfiguredClientCompression` keeps ordinary-mode codecs |

## G9 transport (new test, `FiniteG9BrokerTransportTest`)

Opt-in (`FINITE_G9_BOOTSTRAP`). Uses production `managedStoreBuilder()` and
`FiniteLifecycleCaptureRuntime.liveProperties`. Synthetic 6,307,840-byte value (never typed
authority). Flow: producer → changelog → read_committed fetch → local-state loss → restore.

- Broker: disposable `redpandadata/redpanda:v26.2.3` (cached image, single node, 2 GiB limit, no auth), removed after run.
- Result: 1 test, 0 skipped, 0 failures, 48.7 s. Changelog topic `max.message.bytes=6308864`; one committed record of exactly 6,307,840 bytes; restored bytes equal.
- Limits: unauthenticated broker (SASL/ACL behaviour is covered by Codex's earlier MAIN runs, not re-run); RF1; one run, no soak; Redpanda v26.2.3, not the compose-pinned v26.2.4.

## Other results (same source)

| Check | Result |
| --- | --- |
| `go build ./... && go vet ./...` (matching-engine) | exit 0 |
| `go test ./...` (matching-engine) | all packages ok |
| `go test -race ./internal/app ./internal/streamdirect` | ok |
| Kotlin `com.reef.platform.calcify.Finite*` before Postgres/G9 env | 81 tests, 0 failures, 0 errors, 11 skipped (env-gated) |
| Postgres-backed `FiniteBindingStorePostgresTest` + `FiniteIngressTest` | 10 + 11 tests, 0 skipped, 0 failures |
| `git diff --check`, `check-records-retention.mjs` | pass (520 archived files) |
| `scripts/generate-proto.sh` | no generated-source drift |

Postgres fixture: disposable `postgres:16`, `boundary` migrations 0001–0013 applied by the
owner role, then `EXECUTE` granted per function to two login roles (registrar: register,
stages, release, snapshot; runtime: claim_owner, begin_attempt, authorize_send, ack, unknown,
stop, snapshot, receipt). Grants were inferred from the tests' call sites, not taken from an
operator runbook — the production role split still needs an owner-reviewed definition. First
attempt failed 8/21 because only migration 0013 was applied (missing
`boundary.stream_command_intake`); that was fixture setup, not a product defect, and is kept here.
Container and password files removed after the run.

## CI-readiness check (local replay of the `ci.yml` jobs O2 touches)

Replayed in a clean `git worktree` where relevant (local `artifacts/` holds a broken symlink that
makes `script-surface-check` crash only in the dirty checkout).

| CI job / step | Before | Cause | Fix |
| --- | --- | --- | --- |
| node-dev-tooling: `script-surface-check` | exit 1 | `finite-o2-{engine-adverse,proof,resource-monitor}.test.mjs` not wired anywhere | New Bun step in `ci.yml` |
| node-dev-tooling / postgres-schema-placement: `migrate.test.mjs` | 1 fail | expected runtime migration list lacked `0076_calcify_finite_topic_roles.sql` | Added to expected list |
| node-dev-tooling: 38 CI-listed test files under `node --test` | 280/281 pass before fix | the `migrate.test.mjs` case above | 281/281 expected after fix; `migrate` + `ci-workflow-hardening` re-run: 26/26 |
| Bun step contents under Bun 1.3.14 (CI pin) | n/a | n/a | 151 pass, 0 fail, 27 s |
| postgres-schema-placement: migrations on fresh `postgres:16` | n/a | n/a | runtime 0001–0076 and boundary 0001–0013 apply with no error (not the full `make dev-db-migrate` multi-database path) |

Correction: an earlier draft of this record called `proof-supervisor.test.mjs` a pre-existing
failure. That was wrong — it was run with `bun test`, but CI runs it under `node --test`
(Node 22), where it passes (187/187 across the calcify node files). Those 4 Bun-only
timing failures are not a CI condition. Running O2's three Bun-API harness files under plain
`node --test` also fails to load (`bun:` scheme, `Bun` global); they now run only in the Bun step.

Not replayed locally: GitHub-hosted job runtime (ubuntu, Node 22.x, Bun setup action), container
builds, docs-site, scenario-replay, infrastructure-config, dependency-alignment, Gradle
`kotlin-platform-runtime` in full.

## Hygiene change

Three O2 tests wrote receipts into the repository's `.planning/` on every run. Redirected to
`services/platform-runtime/build/finite-o2-receipts/` (git-ignored).

## Not done

- Independent review 2 of 3 (needs a fresh reviewer, no inherited context).
- G1–G8 and G10 live evidence re-run; they remain Codex's recorded claims
  (`.planning/calcify-p3-o2-2026-10-10/manager/`).
- Full `com.reef.platform.calcify.*` regression (Codex measured about 1 h, dominated by unchanged financial tests).
- Owner-doc updates, retention pass and PR packaging for O2.

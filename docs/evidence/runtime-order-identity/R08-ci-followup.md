# R08 CI follow-up

PR463 original head017bf419. CI run36971123714.

## Root causes and fixes
- platform-runtime container job110725154419: processResources referenced migration outside module-only Docker context; unreadable root input hashing failed on /sys. Both runtime builders now copy same migration from repository-root context. CI/publishing/Compose/local-deploy callers aligned. File collection explicit; prerequisite verification fails when migration absent. No duplicate SQL source.
- postgres-schema-placement job110725154309: Arena lifecycle fixture wrote blank-run dirty marker for nonblank-run order. Both marker writes and lifecycle queries now scoped; timestamp/noop-rewrite/dirty-clear assertions retained.
- ci-required aggregate failure reflects those gates.

## Checks
- CI failures preserved in /private/tmp/r08-ci-container-failure.log and r08-ci-schema-failure.log.
- Schema RED reproduced unchanged fixture: ./services/platform-runtime/gradlew --no-daemon -p services/arena-control-plane test --tests '*PostgresSchemaMigrationIntegrationTest.lifecycleProjectionClearsDuplicateDirtyWithoutNoopRewrite'; failed original line385, log r08-ci-schema-red.log.
- Full schema placement GREEN: same Gradle command with --tests com.reef.arena.controlplane.infrastructure.persistence.PostgresSchemaMigrationIntegrationTest;15 tests0 failures/errors/skips. Runtime/arena JDBC own postgres17 tmpfs container55448, separate reef_r08_ci and reef_r08_arena DBs. Full current migration chains applied via exported discoverMigrations/buildApplySql, including migration ledger.
- Runtime final docker build -f services/platform-runtime/Dockerfile -t reef/r08-runtime-ci:check . GREEN. Final image jar resource bytes match migration SHA2568dc299521700d51eea2e7d2dac2bf57509b0c6352e67de6d820b90d1ad67c6e4.
- Arena docker build -f services/arena-control-plane/Dockerfile -t reef/r08-arena-ci:check . GREEN.
- Missing-resource probe initially exposed doFirst skipped on NO-SOURCE; changed to prerequisite task. Empty isolated module processResources --offline now fails explicit Missing runtime order identity bootstrap migration. Expected negative check, not failed final positive build.
- node --test scripts/dev/lib/dev-stack.test.mjs scripts/dev/lib/dev-profiles.test.mjs scripts/dev/db/migrate.test.mjs:31 passed0 skipped.
- node scripts/ci/check-dependency-alignment.mjs passed; node --check scripts/deploy/hetzner-core.mjs passed.
- Compose base/local and base/local/calcify config --quiet passed; git diff --check passed.

## Limits
No deploy/merge. CI follow-up fixes packaging/configuration/test fixture; runtime identity behavior unchanged. Independent review budget remains3/3 consumed; no fourth reviewer started or readiness claim for new scope based on fresh independent review. Remote CI verification pending push. Dedicated test resources cleaned after checks.

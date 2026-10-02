# R08 runtime order isolation

Baseline/head before review: 41c14d6a57b0da2e5f9e8df25c626249a659eb68
Branch: codex/runtime-order-run-isolation
Issue: https://github.com/dills122/reef/issues/448
Canonical body: /Users/dsteele/.codex/worktrees/a184/reef/.planning/issues/R08.md

## Plan and implementation
1. Reproduce overwrite in memory and isolated real Postgres: complete; acceptance assertions failed before scoped keys.
2. Audit typed identity propagation, storage/query keys, event scope, downstream joins, lifecycle dirty/rebuild paths: implemented.
3. Add composite identity, replay/cancel/modify/settlement regressions, versioned migration and legacy recovery/rollout policy: implemented. Migration restores local canonical acceptance snapshots and proven event scopes; external canonical sources require normal scoped replay.
4. Focused tests, runtime check and independent review: tests complete; review 1 returned Not ready; finding accepted and fixed; review 2 pending, count 1 of maximum 3.

## Executed verification
All Gradle commands use JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home and module services/platform-runtime.
- Acceptance RED memory /private/tmp/r08-memory-red.log; real PG /private/tmp/r08-postgres-red.log. Expected two runs, got only later run.
- Scoped acceptance/history GREEN /private/tmp/r08-first-green.log, /private/tmp/r08-pg-history.log.
- Bulk projection RED /private/tmp/r08-bulk-red.log (expected partial fill, got open), GREEN /private/tmp/r08-bulk-green-2.log after migration wrapper fix.
- Idempotent replay RED /private/tmp/r08-replay-red.log (duplicated fills), GREEN /private/tmp/r08-replay-green.log.
- Canonical replay RED /private/tmp/r08-canonical-red.log using temporary removal of own canonical writes; GREEN /private/tmp/r08-canonical-green.log after restoring scoped converter.
- Submit-result reload RED /private/tmp/r08-result-red.log (cross-run fills), corrected scope. Intermediate result-green had schema fixture expectation failure; later suite passed.
- Migration upgrade GREEN /private/tmp/r08-upgrade.log: pre0073 overwrite restored from canonical snapshots, unproven legacy event remains blank.
- ./gradlew test full suite GREEN /private/tmp/r08-module-3.log. Earlier failures from additive JSON/schema fixture expectations and missing scoped fixture metadata corrected.
- ./gradlew check GREEN /private/tmp/r08-check.log (full suite + Jacoco gate). No JDBC env; DB-dependent checks in this run are not claimed executed.
- Real DB selected GREEN /private/tmp/r08-selected-integration-2.log: ./gradlew test --tests '*PostgresRunOrder*' --tests '*PostgresVenueEventBatchMaterializationIntegrationTest' --tests '*PostgresLifecycleNumericParityIntegrationTest' --tests '*PostgresDirtyProjectionConcurrencyIntegrationTest' --tests '*TradeSettlementObligationMaterializerTest'. JDBC localhost:55448/reef_r08_migrated, isolated postgres:17, full production migration chain. First selected run failed obsolete0065 fixture installation, corrected scoped keys/lock-only double patch while retaining race assertions.
- Authorization rejection scope RED /private/tmp/r08-auth-red.log (2 of13 failures), GREEN /private/tmp/r08-auth-green.log via ./gradlew test --tests '*OrderApplicationServiceTest' --tests '*TradeSettlementObligationMaterializerTest'. Includes real settlement materializer ownership regression after reused order IDs.
- node --test scripts/dev/db/migrate.test.mjs GREEN /private/tmp/r08-migration-tests.log, 25 passed, 0 failed/skipped.
- git diff --check GREEN.

## Boundaries
No shared stack changes, merge, deployment, performance claim, or engine wire-contract changes. Dedicated test DB only. Migration forward-only: old order-only conflict target images incompatible. Unknown historical attribution retained blank, not guessed from overwritten acceptance. Review must assess these limits against requirements.

## Independent review 1 of 3
Verdict Not ready. P2 accepted: in-memory canonical cancel/modify lacked captured payload, so events stayed blank-run. Added captured payload lookup dependency and parity regression. RED /private/tmp/r08-review1-red.log (lifecycle scoped event absent); GREEN memory /private/tmp/r08-review1-memory-green.log. First combined fix run also exposed stale cached lifecycle test assertion (missing explicit dirty projection) and dedicated Docker DB down after disk-full crash; neither counted as successful verification. Recreated only R08 test container/own volume, now tmpfs768m, rebuilt exact full migration chain /private/tmp/r08-db-recreate.log. Corrected test projection step. Real DB + memory focused GREEN /private/tmp/r08-review1-green-2.log: 61 tests, zero failures/errors/skips. Full check rerunning /private/tmp/r08-check-review2.log.

## Independent review 2 of 3
Verdict Not ready; no heavy pivot required. P1 Accept: normal replay into upgraded unknown blank-run facts conflicts, reviewer proved exact migration/full prechain in rolled-back transaction /private/tmp/r08-review2-replay.log. Corrected rollout to fresh projection DB/schema, existing evidence retained, captured scope required, watermarks fresh, explicit validation/cutover. New realPG regression confirms existing store conflict then successful normal canonical projector fresh rebuild/replay, both run identities, original legacy facts unchanged. First recovery fixture failed old single-key upsert of two duplicate IDs in one batch; corrected sequential legacy writes (matching original bug), then GREEN /private/tmp/r08-review2-recovery-green-2.log.
P2 Accept: optional captured lookup wasn't bound by production composition. Added CapturedCommandPayloadLookup on supported captures; HTTP server binds real source through API/service/persistence before loops. Composition RED /private/tmp/r08-review2-composition-red.log, GREEN /private/tmp/r08-review2-composition-green.log. Final composition regression exercises plain capture and command-log wrapper with default-constructed memory persistence, no manual resolver injection.
Focused realPG/memory/command capture suite GREEN /private/tmp/r08-review3-focused.log:75 tests, 0 failures/errors/skips. Full check GREEN /private/tmp/r08-check-review3.log; includes expanded composition regression and Jacoco gate, no JDBC env so DB skips not SQL evidence. git diff --check GREEN. Review3 pending, maximum3 final pass.

## Final review and delivery
Independent review3/3 Ready, no actionable findings; see R08-review-3.md. Both earlier findings accepted/fixed and realDB regressions added. Review budget exhausted, no further instance.
Final fullcheck:706 tests,0 failures/errors,5 explicit DB skips; Jacoco gate passed. Final focused realDB author suite75 tests no skips; reviewer independently12 tests no skips plus canonicalSQL probe. Durable selected raw logs in R08-evidence/ (original /private/tmp paths above retained as execution history). Production provisioning/cutover/rollback not executed; no performance claim. Dedicated R08 test container and temporary data removed after all reviewer checks; shared stack untouched. Branch only, no merge/deploy.

Staged diff check caught trailing spaces in copied PostgreSQL aligned output and extra EOF blank line in formerly untracked converter. Trimmed whitespace only; original raw /private/tmp logs unchanged, durable copies normalized. No behavior change after final review.

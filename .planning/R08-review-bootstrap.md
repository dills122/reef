# Fresh Review Bootstrap

Review instance: 3 of 3

## Review Objective
Independent engineering review of R08/#448 runtime order run isolation implementation AND plan, correctness, style, migration and acceptance coverage. Read-only. No edits/commits or additional streams.

## Repository And Worktree
/Users/dsteele/.codex/worktrees/runtime-order-run-isolation/reef
Primary shared checkout excluded: /Users/dsteele/repos/reef
Read AGENTS.md, docs/AI_CONTEXT.md, docs/README.md, delivery policy and task-relevant steering. Use installed codebase-memory skill for structural exploration; graph may be stale; check coverage and source fallback when needed.

## Base, Head, Branch, And Dirty State
Base = HEAD = 41c14d6a57b0da2e5f9e8df25c626249a659eb68
Branch codex/runtime-order-run-isolation. Review tracked diff against HEAD plus all listed untracked source/test/migration/docs. Author will freeze implementation during review.

 M docs/COMMAND_INTAKE_PROCESS.md
 M docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md
 M docs/steering/external-api-boundary.md
 M scripts/dev/db/migrate.test.mjs
 M services/platform-runtime/build.gradle.kts
 M services/platform-runtime/src/main/kotlin/com/reef/platform/api/CommandCaptureStore.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/TradeSettlementObligationMaterializer.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/domain/Models.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/InMemoryRuntimePersistence.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresBootstrap.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimeJson.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt
 M services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/RuntimePersistence.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/api/PlatformApiTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/api/PlatformHttpServerBoundaryTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/application/OrderApplicationServiceTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/application/settlement/TradeSettlementObligationMaterializerTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/InMemoryRuntimePersistenceTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/PostgresDirtyProjectionConcurrencyIntegrationTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/PostgresLifecycleNumericParityIntegrationTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimeJsonTest.kt
 M services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/PostgresSchemaRequirementsTest.kt
?? .planning/R08-author-explanation.md
?? .planning/R08-ledger.md
?? .planning/R08-plan.md
?? .planning/R08-review-1.md
?? .planning/R08-review-2.md
?? .planning/R08-review-bootstrap.md
?? docs/RUNTIME_ORDER_IDENTITY.md
?? scripts/dev/db/migrations/runtime/0073_runtime_order_run_identity.sql
?? services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/RuntimeOutcomeMaterialization.kt
?? services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/PostgresRunOrderIdentityMigrationIntegrationTest.kt
?? services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/PostgresRunOrderIsolationIntegrationTest.kt
?? services/platform-runtime/src/test/kotlin/com/reef/platform/infrastructure/persistence/RuntimeOrderIsolationAssertions.kt

## In-Scope Commits And Paths
No new commits. All listed modifications and untracked files except review packets (administrative); inspect source untracked files directly because git diff omits them.

## Canonical Requirements And Plan
Canonical issue body: /Users/dsteele/.codex/worktrees/a184/reef/.planning/issues/R08.md
Issue URL https://github.com/dills122/reef/issues/448
Plan: .planning/R08-plan.md. Evidence ledger and prior review reports are author testimony; read only after preliminary pass.
Acceptance: two runs sharing order IDs and lane/session/instrument keep ownership/history; replay, cancellation/modify, settlement attribution; Postgres/in-memory parity. Typed identity throughout storage/query/event/joins; recover projections from canonical source, not read-only filter after overwrite.

## Explicit Exclusions
Other chats, shared primary dirty files, engine identity redesign, Calcify R06, unrelated post-trade issues. No merge/deploy/performance qualification. Do not let exclusions hide introduced defects.

## Verification Commands Available To Reviewer
Use Java21 JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home. Module services/platform-runtime: ./gradlew check --console=plain; focused test patterns *PostgresRunOrder*, *PostgresDirtyProjectionConcurrencyIntegrationTest, *PostgresLifecycleNumericParityIntegrationTest, *TradeSettlementObligationMaterializerTest, *OrderApplicationServiceTest. Tests write build artifacts; require escalated sandbox for worktree. Real test DB JDBC jdbc:postgresql://127.0.0.1:55448/reef_r08_migrated user/password reef (dedicated test-only container reef-r08-postgres, postgres17). New PG tests assume env, use unique schemas. node --test scripts/dev/db/migrate.test.mjs; git diff --check.

## Author Explanation Location Or Delivery Step
.planning/R08-author-explanation.md. Inspect implementation/tests/canonical requirement and record preliminary findings BEFORE reading this packet. Then reconcile claims.

Use $independent-review in reviewer mode. This is review instance 3 of 3. Work from Fresh Review Bootstrap first and record preliminary review before reading Author Explanation. Then verify explanation against repository, review both implementation and plan, run proportionate non-mutating checks, return evidence-backed verdict. Do not implement fixes, create further review instances, or split work into new workstreams.

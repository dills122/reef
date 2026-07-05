package com.reef.platform.application.arena

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArenaControlPlaneServiceTest {
    private val fixedNow = Instant.parse("2026-07-05T12:00:00Z")

    @Test
    fun registersBotAndVersionSourceFacts() {
        val store = InMemoryArenaBotRegistryStore()
        val service = ArenaControlPlaneService(store) { fixedNow }

        val bot = service.registerBot(registerBotCommand())
        val version = service.registerVersion(registerVersionCommand())

        assertEquals("sample-bot", bot.botId)
        assertEquals("sample-bot.ts", bot.fileName)
        assertEquals("publisher@example.com", bot.metadata.email)
        assertEquals(ArenaBotVersionStatus.Draft, version.status)
        assertEquals("sha256:source", version.sourceHash)
        assertEquals("sha256:artifact", version.artifactHash)
        assertEquals("1.5.0", version.sdkVersion)
        assertEquals("v1", version.apiVersion)
        assertEquals("sha256:deps", version.dependencyManifestHash)
    }

    @Test
    fun requiresUniqueFileNamesAcrossBots() {
        val service = ArenaControlPlaneService(InMemoryArenaBotRegistryStore()) { fixedNow }
        service.registerBot(registerBotCommand())

        assertFailsWith<IllegalArgumentException> {
            service.registerBot(
                registerBotCommand(botId = "other-bot", name = "Other Bot")
            )
        }
    }

    @Test
    fun recordsQualificationReports() {
        val store = InMemoryArenaBotRegistryStore()
        val service = seededService(store)

        service.recordQualificationReport(
            botId = "sample-bot",
            versionId = "v1",
            reportId = "report-1",
            status = ArenaQualificationStatus.Failed,
            issues = listOf("external network import blocked"),
            policyVersion = "policy-2026-07-05"
        )

        val reports = store.qualificationReports("sample-bot", "v1")
        assertEquals(1, reports.size)
        assertEquals(ArenaQualificationStatus.Failed, reports.single().status)
        assertEquals(listOf("external network import blocked"), reports.single().issues)
    }

    @Test
    fun recordsOperatorDecisionsForValidLifecycleTransitions() {
        val store = InMemoryArenaBotRegistryStore()
        val service = seededService(store)

        service.transitionVersion("sample-bot", "v1", ArenaBotVersionStatus.Submitted, "operator-1", "submit", "corr-1")
        service.transitionVersion(
            "sample-bot",
            "v1",
            ArenaBotVersionStatus.ChecksPassed,
            "operator-1",
            "checks passed",
            "corr-2"
        )
        val approved = service.transitionVersion(
            "sample-bot",
            "v1",
            ArenaBotVersionStatus.Approved,
            "operator-2",
            "approved for arena",
            "corr-3"
        )

        assertEquals(ArenaBotVersionStatus.Approved, approved.status)
        assertTrue(service.mayAcceptRuntimeCommands("sample-bot", "v1"))
        assertEquals(3, store.operatorDecisions("sample-bot", "v1").size)
        assertEquals("operator-2", store.operatorDecisions("sample-bot", "v1").last().actorId)
    }

    @Test
    fun rejectsInvalidLifecycleTransitions() {
        val service = seededService(InMemoryArenaBotRegistryStore())

        assertFailsWith<IllegalArgumentException> {
            service.transitionVersion(
                "sample-bot",
                "v1",
                ArenaBotVersionStatus.Active,
                "operator-1",
                "skip approval",
                "corr-1"
            )
        }
    }

    @Test
    fun blocksRuntimeCommandsForDisabledLifecycleStates() {
        val service = seededService(InMemoryArenaBotRegistryStore())

        assertFalse(service.mayAcceptRuntimeCommands("sample-bot", "v1"))
        service.transitionVersion("sample-bot", "v1", ArenaBotVersionStatus.Submitted, "operator-1", "submit", "corr-1")
        service.transitionVersion(
            "sample-bot",
            "v1",
            ArenaBotVersionStatus.Quarantined,
            "operator-2",
            "scanner regression",
            "corr-2"
        )

        assertFalse(service.mayAcceptRuntimeCommands("sample-bot", "v1"))
    }

    @Test
    fun registersRunRecordsForApprovedBotVersions() {
        val store = InMemoryArenaBotRegistryStore()
        val service = approvedService(store)

        val run = service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
            )
        )

        assertEquals(ArenaRunStatus.Planned, run.status)
        assertEquals("policy-2026-07-05", run.policyVersion)
        assertEquals(listOf(ArenaRunBotVersionRef("sample-bot", "v1")), run.botVersions)
        assertEquals(run, store.runRecord("run-1"))
    }

    @Test
    fun rejectsRunRecordsForUnapprovedBotVersions() {
        val service = seededService(InMemoryArenaBotRegistryStore())

        assertFailsWith<IllegalArgumentException> {
            service.registerRun(
                RegisterArenaRunCommand(
                    runId = "run-1",
                    modeId = "momentum",
                    scenarioId = "scenario-a",
                    seed = 42L,
                    policyVersion = "policy-2026-07-05",
                    botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
                )
            )
        }
    }

    @Test
    fun tracksRunLifecycleWithTerminalCompletionTimestamp() {
        val service = approvedService(InMemoryArenaBotRegistryStore())
        service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
            )
        )

        val running = service.updateRunStatus("run-1", ArenaRunStatus.Running)
        val completed = service.updateRunStatus("run-1", ArenaRunStatus.Completed)

        assertEquals(ArenaRunStatus.Running, running.status)
        assertEquals(ArenaRunStatus.Completed, completed.status)
        assertEquals(fixedNow, completed.completedAt)
        assertFailsWith<IllegalArgumentException> {
            service.updateRunStatus("run-1", ArenaRunStatus.Running)
        }
    }

    @Test
    fun recordsRunBotResultsAndRanksCompletedRunLeaderboard() {
        val store = InMemoryArenaBotRegistryStore()
        val service = approvedService(store)
        service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
            )
        )
        service.updateRunStatus("run-1", ArenaRunStatus.Running)
        service.updateRunStatus("run-1", ArenaRunStatus.Completed)

        service.recordRunBotResult(
            ArenaRunBotResult(
                runId = "run-1",
                botId = "sample-bot",
                versionId = "v1",
                scoringPolicyVersion = "score-v1",
                finalEquity = 1_025_000,
                realizedPnl = 25_000,
                maxDrawdown = 1_000,
                actionsProposed = 12,
                orderActionsProposed = 8,
                dataCalls = 20,
                signalsGenerated = 4,
                disqualified = false,
                createdAt = fixedNow
            )
        )

        val results = store.runBotResults("run-1")
        val leaderboard = service.leaderboard("momentum", "score-v1")

        assertEquals(1, results.size)
        assertEquals(1_025_000, results.single().finalEquity)
        assertEquals(1, leaderboard.single().rank)
        assertEquals("sample-bot", leaderboard.single().botId)
    }

    @Test
    fun keepsRunBotResultsSeparateByScoringPolicyVersion() {
        val store = InMemoryArenaBotRegistryStore()
        val service = approvedService(store)
        service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
            )
        )

        service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_025_000))
        service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v2", finalEquity = 1_030_000))
        service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_026_000))

        val results = store.runBotResults("run-1").sortedBy { it.scoringPolicyVersion }

        assertEquals(2, results.size)
        assertEquals("score-v1", results[0].scoringPolicyVersion)
        assertEquals(1_026_000, results[0].finalEquity)
        assertEquals("score-v2", results[1].scoringPolicyVersion)
        assertEquals(1_030_000, results[1].finalEquity)
    }

    @Test
    fun rejectsInvalidRunBotResultCounters() {
        val service = approvedService(InMemoryArenaBotRegistryStore())
        service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
            )
        )

        assertFailsWith<IllegalArgumentException> {
            service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_025_000).copy(
                actionsProposed = 1,
                orderActionsProposed = 2
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_025_000).copy(
                maxDrawdown = -1
            ))
        }
    }

    @Test
    fun readsRunBotResultsForRegisteredRun() {
        val store = InMemoryArenaBotRegistryStore()
        val service = approvedService(store)
        service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
            )
        )
        service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_025_000))

        val results = service.runBotResults("run-1")

        assertEquals(1, results.size)
        assertEquals("score-v1", results.single().scoringPolicyVersion)
        assertFailsWith<IllegalArgumentException> { service.runBotResults("missing-run") }
    }

    @Test
    fun replacesRuntimeConfigDescriptorsWithoutSecretValues() {
        val store = InMemoryArenaBotRegistryStore()
        val service = seededService(store)

        val descriptors = service.replaceRuntimeConfigDescriptors(
            "sample-bot",
            "v1",
            listOf(
                ArenaRuntimeConfigDescriptor(
                    botId = "sample-bot",
                    versionId = "v1",
                    key = "apiKey",
                    provider = ArenaRuntimeConfigProvider.OpenBao,
                    secretPath = "secret/data/arena/sample-bot/v1/api-key",
                    required = true,
                    description = "market data provider credential"
                )
            )
        )

        assertEquals(descriptors, store.runtimeConfigDescriptors("sample-bot", "v1"))
        assertEquals("secret/data/arena/sample-bot/v1/api-key", descriptors.single().secretPath)
    }

    @Test
    fun rejectsUnsafeRuntimeConfigDescriptorKeys() {
        val service = seededService(InMemoryArenaBotRegistryStore())

        assertFailsWith<IllegalArgumentException> {
            service.replaceRuntimeConfigDescriptors(
                "sample-bot",
                "v1",
                listOf(
                    ArenaRuntimeConfigDescriptor(
                        botId = "sample-bot",
                        versionId = "v1",
                        key = "api-key",
                        provider = ArenaRuntimeConfigProvider.OpenBao,
                        secretPath = "secret/data/arena/sample-bot/v1/api-key",
                        required = true
                    )
                )
            )
        }
    }

    private fun approvedService(store: InMemoryArenaBotRegistryStore): ArenaControlPlaneService {
        return seededService(store).also { service ->
            service.transitionVersion("sample-bot", "v1", ArenaBotVersionStatus.Submitted, "operator-1", "submit", "corr-1")
            service.transitionVersion(
                "sample-bot",
                "v1",
                ArenaBotVersionStatus.ChecksPassed,
                "operator-1",
                "checks passed",
                "corr-2"
            )
            service.transitionVersion(
                "sample-bot",
                "v1",
                ArenaBotVersionStatus.Approved,
                "operator-2",
                "approved",
                "corr-3"
            )
        }
    }

    private fun seededService(store: InMemoryArenaBotRegistryStore): ArenaControlPlaneService {
        return ArenaControlPlaneService(store) { fixedNow }.also { service ->
            service.registerBot(registerBotCommand())
            service.registerVersion(registerVersionCommand())
        }
    }

    private fun registerBotCommand(
        botId: String = "sample-bot",
        name: String = "Sample Bot"
    ): RegisterArenaBotCommand {
        return RegisterArenaBotCommand(
            botId = botId,
            fileName = "sample-bot.ts",
            metadata = ArenaBotMetadata(
                name = name,
                publisher = "Sample Publisher",
                email = "publisher@example.com",
                description = "test bot",
                version = "1.0.0"
            )
        )
    }

    private fun registerVersionCommand(): RegisterArenaBotVersionCommand {
        return RegisterArenaBotVersionCommand(
            botId = "sample-bot",
            versionId = "v1",
            sourceHash = "sha256:source",
            artifactHash = "sha256:artifact",
            sdkVersion = "1.5.0",
            apiVersion = "v1",
            dependencyManifestHash = "sha256:deps"
        )
    }

    private fun runBotResult(
        scoringPolicyVersion: String,
        finalEquity: Long
    ): ArenaRunBotResult {
        return ArenaRunBotResult(
            runId = "run-1",
            botId = "sample-bot",
            versionId = "v1",
            scoringPolicyVersion = scoringPolicyVersion,
            finalEquity = finalEquity,
            realizedPnl = finalEquity - 1_000_000,
            maxDrawdown = 1_000,
            actionsProposed = 12,
            orderActionsProposed = 8,
            dataCalls = 20,
            signalsGenerated = 4,
            disqualified = false,
            createdAt = fixedNow
        )
    }
}

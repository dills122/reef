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
    fun keepsDisqualifiedRunBotResultsOutOfLeaderboard() {
        val store = InMemoryArenaBotRegistryStore()
        val service = approvedService(store)
        service.registerBot(registerBotCommand(botId = "bad-bot", fileName = "bad-bot.ts", name = "Bad Bot"))
        service.registerVersion(registerVersionCommand(botId = "bad-bot"))
        service.transitionVersion("bad-bot", "v1", ArenaBotVersionStatus.Submitted, "operator-1", "submit", "corr-4")
        service.transitionVersion("bad-bot", "v1", ArenaBotVersionStatus.ChecksPassed, "operator-1", "checks passed", "corr-5")
        service.transitionVersion("bad-bot", "v1", ArenaBotVersionStatus.Approved, "operator-2", "approved", "corr-6")
        service.registerRun(
            RegisterArenaRunCommand(
                runId = "run-1",
                modeId = "momentum",
                scenarioId = "scenario-a",
                seed = 42L,
                policyVersion = "policy-2026-07-05",
                botVersions = listOf(
                    ArenaRunBotVersionRef("sample-bot", "v1"),
                    ArenaRunBotVersionRef("bad-bot", "v1")
                )
            )
        )
        service.updateRunStatus("run-1", ArenaRunStatus.Running)
        service.updateRunStatus("run-1", ArenaRunStatus.Completed)

        service.recordRunBotResult(runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_025_000))
        service.recordRunBotResult(
            runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_500_000).copy(
                botId = "bad-bot",
                disqualified = true
            )
        )

        val results = store.runBotResults("run-1")
        val leaderboard = service.leaderboard("momentum", "score-v1")

        assertEquals(2, results.size)
        assertTrue(results.any { it.botId == "bad-bot" && it.disqualified })
        assertEquals(listOf("sample-bot"), leaderboard.map { it.botId })
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

    @Test
    fun rejectsRegisterBotWithBlankFieldsOrBadEmail() {
        val service = ArenaControlPlaneService(InMemoryArenaBotRegistryStore()) { fixedNow }
        assertFailsWith<IllegalArgumentException> { service.registerBot(registerBotCommand().copy(botId = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerBot(registerBotCommand().copy(fileName = "")) }
        assertFailsWith<IllegalArgumentException> {
            service.registerBot(registerBotCommand().copy(metadata = registerBotCommand().metadata.copy(name = "")))
        }
        assertFailsWith<IllegalArgumentException> {
            service.registerBot(registerBotCommand().copy(metadata = registerBotCommand().metadata.copy(publisher = "")))
        }
        assertFailsWith<IllegalArgumentException> {
            service.registerBot(registerBotCommand().copy(metadata = registerBotCommand().metadata.copy(email = "not-an-email")))
        }
    }

    @Test
    fun rejectsRegisterBotWithDuplicateBotId() {
        val service = ArenaControlPlaneService(InMemoryArenaBotRegistryStore()) { fixedNow }
        service.registerBot(registerBotCommand())
        assertFailsWith<IllegalArgumentException> { service.registerBot(registerBotCommand()) }
    }

    @Test
    fun rejectsRegisterVersionWithUnknownBotOrBlankFieldsOrDuplicate() {
        val store = InMemoryArenaBotRegistryStore()
        val service = ArenaControlPlaneService(store) { fixedNow }

        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand()) }

        service.registerBot(registerBotCommand())
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand().copy(versionId = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand().copy(sourceHash = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand().copy(artifactHash = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand().copy(sdkVersion = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand().copy(apiVersion = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand().copy(dependencyManifestHash = "")) }

        service.registerVersion(registerVersionCommand())
        assertFailsWith<IllegalArgumentException> { service.registerVersion(registerVersionCommand()) }
    }

    @Test
    fun rejectsRecordQualificationReportWithBlankFieldsOrUnknownVersion() {
        val service = seededService(InMemoryArenaBotRegistryStore())
        assertFailsWith<IllegalStateException> {
            service.recordQualificationReport("sample-bot", "missing", "r1", ArenaQualificationStatus.Passed, emptyList(), "p1")
        }
        assertFailsWith<IllegalArgumentException> {
            service.recordQualificationReport("sample-bot", "v1", "", ArenaQualificationStatus.Passed, emptyList(), "p1")
        }
        assertFailsWith<IllegalArgumentException> {
            service.recordQualificationReport("sample-bot", "v1", "r1", ArenaQualificationStatus.Passed, emptyList(), "")
        }
    }

    @Test
    fun rejectsTransitionVersionWithBlankActorOrReason() {
        val service = seededService(InMemoryArenaBotRegistryStore())
        assertFailsWith<IllegalArgumentException> {
            service.transitionVersion("sample-bot", "v1", ArenaBotVersionStatus.Submitted, "", "submit", "corr-1")
        }
        assertFailsWith<IllegalArgumentException> {
            service.transitionVersion("sample-bot", "v1", ArenaBotVersionStatus.Submitted, "operator-1", "", "corr-1")
        }
    }

    @Test
    fun canTransitionCoversFullLifecycleMatrix() {
        fun transition(from: ArenaBotVersionStatus, to: ArenaBotVersionStatus): Boolean {
            val store = InMemoryArenaBotRegistryStore()
            val svc = ArenaControlPlaneService(store) { fixedNow }
            svc.registerBot(registerBotCommand())
            svc.registerVersion(registerVersionCommand())
            store.saveVersion(store.version("sample-bot", "v1")!!.copy(status = from))
            return try {
                svc.transitionVersion("sample-bot", "v1", to, "operator-1", "reason", "corr-1")
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        // same-state no-op always allowed
        ArenaBotVersionStatus.entries.forEach { assertTrue(transition(it, it)) }
        // any -> Archived allowed, any (non-Banned) -> Quarantined allowed, any -> Banned allowed
        ArenaBotVersionStatus.entries.forEach { from ->
            assertTrue(transition(from, ArenaBotVersionStatus.Archived))
            assertTrue(transition(from, ArenaBotVersionStatus.Banned))
        }
        assertFalse(transition(ArenaBotVersionStatus.Banned, ArenaBotVersionStatus.Quarantined))
        assertTrue(transition(ArenaBotVersionStatus.Active, ArenaBotVersionStatus.Quarantined))

        assertTrue(transition(ArenaBotVersionStatus.Draft, ArenaBotVersionStatus.Submitted))
        assertFalse(transition(ArenaBotVersionStatus.Draft, ArenaBotVersionStatus.ChecksPassed))
        assertTrue(transition(ArenaBotVersionStatus.Submitted, ArenaBotVersionStatus.ChecksPassed))
        assertFalse(transition(ArenaBotVersionStatus.Submitted, ArenaBotVersionStatus.Approved))
        assertTrue(transition(ArenaBotVersionStatus.ChecksPassed, ArenaBotVersionStatus.Approved))
        assertFalse(transition(ArenaBotVersionStatus.ChecksPassed, ArenaBotVersionStatus.Active))
        assertTrue(transition(ArenaBotVersionStatus.Approved, ArenaBotVersionStatus.Active))
        assertTrue(transition(ArenaBotVersionStatus.Approved, ArenaBotVersionStatus.Suspended))
        assertFalse(transition(ArenaBotVersionStatus.Approved, ArenaBotVersionStatus.Draft))
        assertTrue(transition(ArenaBotVersionStatus.Active, ArenaBotVersionStatus.Suspended))
        assertFalse(transition(ArenaBotVersionStatus.Active, ArenaBotVersionStatus.Approved))
        assertTrue(transition(ArenaBotVersionStatus.Suspended, ArenaBotVersionStatus.Active))
        assertFalse(transition(ArenaBotVersionStatus.Suspended, ArenaBotVersionStatus.Draft))
        assertTrue(transition(ArenaBotVersionStatus.Quarantined, ArenaBotVersionStatus.Suspended))
        assertFalse(transition(ArenaBotVersionStatus.Quarantined, ArenaBotVersionStatus.Active))
        assertFalse(transition(ArenaBotVersionStatus.Banned, ArenaBotVersionStatus.Suspended))
        assertFalse(transition(ArenaBotVersionStatus.Archived, ArenaBotVersionStatus.Draft))
    }

    @Test
    fun canTransitionRunCoversFullMatrix() {
        fun transition(from: ArenaRunStatus, to: ArenaRunStatus): Boolean {
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
            store.saveRunRecord(store.runRecord("run-1")!!.copy(status = from))
            return try {
                service.updateRunStatus("run-1", to)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        ArenaRunStatus.entries.forEach { assertTrue(transition(it, it)) }
        assertTrue(transition(ArenaRunStatus.Planned, ArenaRunStatus.Running))
        assertTrue(transition(ArenaRunStatus.Planned, ArenaRunStatus.Cancelled))
        assertFalse(transition(ArenaRunStatus.Planned, ArenaRunStatus.Completed))
        assertTrue(transition(ArenaRunStatus.Running, ArenaRunStatus.Completed))
        assertTrue(transition(ArenaRunStatus.Running, ArenaRunStatus.Failed))
        assertTrue(transition(ArenaRunStatus.Running, ArenaRunStatus.Cancelled))
        assertFalse(transition(ArenaRunStatus.Running, ArenaRunStatus.Planned))
        assertFalse(transition(ArenaRunStatus.Completed, ArenaRunStatus.Running))
        assertFalse(transition(ArenaRunStatus.Failed, ArenaRunStatus.Running))
        assertFalse(transition(ArenaRunStatus.Cancelled, ArenaRunStatus.Running))
    }

    @Test
    fun updateRunStatusFailsForUnknownRun() {
        val service = ArenaControlPlaneService(InMemoryArenaBotRegistryStore()) { fixedNow }
        assertFailsWith<IllegalStateException> { service.updateRunStatus("missing-run", ArenaRunStatus.Running) }
    }

    @Test
    fun rejectsRegisterRunWithBlankFieldsEmptyBotVersionsOrDuplicateId() {
        val service = approvedService(InMemoryArenaBotRegistryStore())
        val validCommand = RegisterArenaRunCommand(
            runId = "run-1",
            modeId = "momentum",
            scenarioId = "scenario-a",
            seed = 42L,
            policyVersion = "policy-2026-07-05",
            botVersions = listOf(ArenaRunBotVersionRef("sample-bot", "v1"))
        )
        assertFailsWith<IllegalArgumentException> { service.registerRun(validCommand.copy(runId = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerRun(validCommand.copy(modeId = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerRun(validCommand.copy(scenarioId = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerRun(validCommand.copy(policyVersion = "")) }
        assertFailsWith<IllegalArgumentException> { service.registerRun(validCommand.copy(botVersions = emptyList())) }

        service.registerRun(validCommand)
        assertFailsWith<IllegalArgumentException> { service.registerRun(validCommand) }
    }

    @Test
    fun rejectsRecordRunBotResultWithBlankFieldsOrUnregisteredBotVersion() {
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
        val base = runBotResult(scoringPolicyVersion = "score-v1", finalEquity = 1_025_000)

        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(runId = "")) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(botId = "")) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(versionId = "")) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(scoringPolicyVersion = "")) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(actionsProposed = -1)) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(orderActionsProposed = -1)) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(dataCalls = -1)) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(signalsGenerated = -1)) }
        assertFailsWith<IllegalStateException> { service.recordRunBotResult(base.copy(runId = "missing-run")) }
        assertFailsWith<IllegalArgumentException> { service.recordRunBotResult(base.copy(botId = "other-bot")) }
    }

    @Test
    fun rejectsLeaderboardWithBlankFieldsAndClampsLimit() {
        val store = InMemoryArenaBotRegistryStore()
        val service = approvedService(store)
        assertFailsWith<IllegalArgumentException> { service.leaderboard("", "score-v1") }
        assertFailsWith<IllegalArgumentException> { service.leaderboard("momentum", "") }
        assertEquals(emptyList(), service.leaderboard("momentum", "score-v1", limit = -5))
        assertEquals(emptyList(), service.leaderboard("momentum", "score-v1", limit = 10_000))
    }

    @Test
    fun rejectsReplaceRuntimeConfigDescriptorsWithMismatchedIdsDuplicateKeysOrBlankSecretPath() {
        val service = seededService(InMemoryArenaBotRegistryStore())
        val descriptor = ArenaRuntimeConfigDescriptor(
            botId = "sample-bot",
            versionId = "v1",
            key = "apiKey",
            provider = ArenaRuntimeConfigProvider.OpenBao,
            secretPath = "secret/data/arena/sample-bot/v1/api-key",
            required = true
        )

        assertFailsWith<IllegalStateException> {
            service.replaceRuntimeConfigDescriptors("sample-bot", "missing", listOf(descriptor))
        }
        assertFailsWith<IllegalArgumentException> {
            service.replaceRuntimeConfigDescriptors("sample-bot", "v1", listOf(descriptor, descriptor))
        }
        assertFailsWith<IllegalArgumentException> {
            service.replaceRuntimeConfigDescriptors("sample-bot", "v1", listOf(descriptor.copy(botId = "other-bot")))
        }
        assertFailsWith<IllegalArgumentException> {
            service.replaceRuntimeConfigDescriptors("sample-bot", "v1", listOf(descriptor.copy(versionId = "other-version")))
        }
        assertFailsWith<IllegalArgumentException> {
            service.replaceRuntimeConfigDescriptors("sample-bot", "v1", listOf(descriptor.copy(secretPath = "")))
        }
    }

    @Test
    fun runBotResultsFailsForUnknownRun() {
        val service = ArenaControlPlaneService(InMemoryArenaBotRegistryStore()) { fixedNow }
        assertFailsWith<IllegalArgumentException> { service.runBotResults("") }
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
        fileName: String = "sample-bot.ts",
        name: String = "Sample Bot"
    ): RegisterArenaBotCommand {
        return RegisterArenaBotCommand(
            botId = botId,
            fileName = fileName,
            metadata = ArenaBotMetadata(
                name = name,
                publisher = "Sample Publisher",
                email = "publisher@example.com",
                description = "test bot",
                version = "1.0.0"
            )
        )
    }

    private fun registerVersionCommand(botId: String = "sample-bot"): RegisterArenaBotVersionCommand {
        return RegisterArenaBotVersionCommand(
            botId = botId,
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

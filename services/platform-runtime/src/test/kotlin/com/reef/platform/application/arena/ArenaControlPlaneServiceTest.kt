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
}

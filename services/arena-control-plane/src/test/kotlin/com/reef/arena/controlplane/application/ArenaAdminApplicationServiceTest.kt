package com.reef.arena.controlplane.application

import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotMetadata
import com.reef.arena.controlplane.arena.ArenaBotRegistryStore
import com.reef.arena.controlplane.arena.ArenaBotVersion
import com.reef.arena.controlplane.arena.ArenaBotVersionStatus
import com.reef.arena.controlplane.arena.ArenaControlPlaneService
import com.reef.arena.controlplane.arena.InMemoryArenaBotRegistryStore
import com.reef.arena.controlplane.arena.ArenaLeaderboardEntry
import com.reef.arena.controlplane.arena.ArenaOperatorDecision
import com.reef.arena.controlplane.arena.ArenaQualificationReport
import com.reef.arena.controlplane.arena.ArenaRunBotResult
import com.reef.arena.controlplane.arena.ArenaRunEnforcementEvent
import com.reef.arena.controlplane.arena.ArenaRunRecord
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import com.reef.arena.controlplane.arena.RegisterArenaBotCommand
import com.reef.arena.controlplane.arena.RegisterArenaBotVersionCommand
import com.reef.platform.api.AccountRiskControl
import com.reef.platform.api.AccountRiskControlStore
import com.reef.platform.api.AccountRiskDecision
import com.reef.platform.application.admin.AdminActor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArenaAdminApplicationServiceTest {
    @Test
    fun constructionDoesNotReadOrWriteAnyStore() {
        // Optional extension discovery must be side-effect free: Reef bootstrap owns
        // its own defaults and this service must not seed either Reef or Arena state.
        ArenaAdminApplicationService(arenaRegistryStore = FailingStore())
    }

    @Test
    fun versionQuarantineSynchronizesBotRiskControl() {
        val arenaStore = seededArenaStore()
        val riskStore = RecordingAccountRiskControlStore()
        val service = ArenaAdminApplicationService(
            arenaRegistryStore = arenaStore,
            accountRiskControlStore = riskStore,
            now = { Instant.parse("2026-05-22T00:00:00Z") }
        )
        val actor = AdminActor("admin-cli", "corr-arena", "2026-05-22T00:00:00Z")

        listOf(
            ArenaBotVersionStatus.Submitted to "submitted",
            ArenaBotVersionStatus.ChecksPassed to "checks passed",
            ArenaBotVersionStatus.Approved to "approved",
            ArenaBotVersionStatus.Quarantined to "scanner regression"
        ).forEach { (status, reason) ->
            service.transitionArenaBotVersion(
                actor,
                ArenaBotVersionDecisionCommand("bot-1", "v1", status, reason)
            )
        }

        val control = riskStore.listControls().single()
        assertEquals("BOT", control.scopeType)
        assertEquals("bot-1", control.scopeId)
        assertEquals(AccountRiskDecision.DISABLED_BOT, control.decision)
        assertTrue(control.reason.contains("Quarantined"))
        assertTrue(control.reason.contains("scanner regression"))
    }

    private fun seededArenaStore(): InMemoryArenaBotRegistryStore {
        val store = InMemoryArenaBotRegistryStore()
        val service = ArenaControlPlaneService(store) { Instant.parse("2026-05-22T00:00:00Z") }
        service.registerBot(
            RegisterArenaBotCommand(
                botId = "bot-1",
                fileName = "bot-1.ts",
                metadata = ArenaBotMetadata("Bot 1", "Publisher", "publisher@example.com")
            )
        )
        service.registerVersion(
            RegisterArenaBotVersionCommand(
                botId = "bot-1",
                versionId = "v1",
                sourceHash = "sha256:source",
                artifactHash = "sha256:artifact",
                sdkVersion = "1.5.0",
                apiVersion = "v1",
                dependencyManifestHash = "sha256:deps"
            )
        )
        return store
    }

    private class FailingStore : ArenaBotRegistryStore {
        private fun unexpected(): Nothing = error("Arena store must not be touched during construction")
        override fun saveBot(bot: ArenaBot): Unit = unexpected()
        override fun bot(botId: String): ArenaBot? = unexpected()
        override fun bots(limit: Int): List<ArenaBot> = unexpected()
        override fun botByFileName(fileName: String): ArenaBot? = unexpected()
        override fun saveVersion(version: ArenaBotVersion): Unit = unexpected()
        override fun version(botId: String, versionId: String): ArenaBotVersion? = unexpected()
        override fun saveQualificationReport(report: ArenaQualificationReport): Unit = unexpected()
        override fun qualificationReports(botId: String, versionId: String): List<ArenaQualificationReport> = unexpected()
        override fun saveOperatorDecision(decision: ArenaOperatorDecision): Unit = unexpected()
        override fun operatorDecisions(botId: String, versionId: String): List<ArenaOperatorDecision> = unexpected()
        override fun saveRunRecord(runRecord: ArenaRunRecord): Unit = unexpected()
        override fun runRecord(runId: String): ArenaRunRecord? = unexpected()
        override fun runs(limit: Int): List<ArenaRunRecord> = unexpected()
        override fun saveRunBotResult(result: ArenaRunBotResult): Unit = unexpected()
        override fun runBotResults(runId: String): List<ArenaRunBotResult> = unexpected()
        override fun saveRunEnforcementEvent(event: ArenaRunEnforcementEvent): Unit = unexpected()
        override fun runEnforcementEvents(runId: String): List<ArenaRunEnforcementEvent> = unexpected()
        override fun leaderboard(modeId: String, scoringPolicyVersion: String, limit: Int): List<ArenaLeaderboardEntry> = unexpected()
        override fun replaceRuntimeConfigDescriptors(botId: String, versionId: String, descriptors: List<ArenaRuntimeConfigDescriptor>): Unit = unexpected()
        override fun runtimeConfigDescriptors(botId: String, versionId: String): List<ArenaRuntimeConfigDescriptor> = unexpected()
    }
}

private class RecordingAccountRiskControlStore : AccountRiskControlStore {
    private val controls = linkedMapOf<String, AccountRiskControl>()

    override fun upsertControl(
        scopeType: String,
        scopeId: String,
        decision: AccountRiskDecision,
        reason: String,
        maxQuantityUnits: String,
        maxNotional: String,
        currency: String
    ) {
        controls["$scopeType|$scopeId"] = AccountRiskControl(
            scopeType = scopeType,
            scopeId = scopeId,
            decision = decision,
            reason = reason,
            maxQuantityUnits = maxQuantityUnits,
            maxNotional = maxNotional,
            currency = currency
        )
    }

    override fun listControls(): List<AccountRiskControl> = controls.values.toList()
}

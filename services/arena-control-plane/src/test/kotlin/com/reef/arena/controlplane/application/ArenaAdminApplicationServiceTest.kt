package com.reef.arena.controlplane.application

import com.reef.arena.controlplane.arena.ArenaBot
import com.reef.arena.controlplane.arena.ArenaBotRegistryStore
import com.reef.arena.controlplane.arena.ArenaBotVersion
import com.reef.arena.controlplane.arena.ArenaLeaderboardEntry
import com.reef.arena.controlplane.arena.ArenaOperatorDecision
import com.reef.arena.controlplane.arena.ArenaQualificationReport
import com.reef.arena.controlplane.arena.ArenaRunBotResult
import com.reef.arena.controlplane.arena.ArenaRunEnforcementEvent
import com.reef.arena.controlplane.arena.ArenaRunRecord
import com.reef.arena.controlplane.arena.ArenaRuntimeConfigDescriptor
import kotlin.test.Test

class ArenaAdminApplicationServiceTest {
    @Test
    fun constructionDoesNotReadOrWriteAnyStore() {
        // Optional extension discovery must be side-effect free: Reef bootstrap owns
        // its own defaults and this service must not seed either Reef or Arena state.
        ArenaAdminApplicationService(arenaRegistryStore = FailingStore())
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

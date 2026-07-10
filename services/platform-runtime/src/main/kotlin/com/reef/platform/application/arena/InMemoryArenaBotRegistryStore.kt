package com.reef.platform.application.arena

class InMemoryArenaBotRegistryStore : ArenaBotRegistryStore {
    private val bots = linkedMapOf<String, ArenaBot>()
    private val botIdsByFileName = linkedMapOf<String, String>()
    private val versions = linkedMapOf<String, ArenaBotVersion>()
    private val reports = linkedMapOf<String, MutableList<ArenaQualificationReport>>()
    private val decisions = linkedMapOf<String, MutableList<ArenaOperatorDecision>>()
    private val runs = linkedMapOf<String, ArenaRunRecord>()
    private val runBotResults = linkedMapOf<String, MutableList<ArenaRunBotResult>>()
    private val runEnforcementEvents = linkedMapOf<String, MutableList<ArenaRunEnforcementEvent>>()
    private val runtimeConfigDescriptors = linkedMapOf<String, List<ArenaRuntimeConfigDescriptor>>()

    override fun saveBot(bot: ArenaBot) {
        bots[bot.botId] = bot
        botIdsByFileName[bot.fileName] = bot.botId
    }

    override fun bot(botId: String): ArenaBot? = bots[botId]

    override fun bots(limit: Int): List<ArenaBot> {
        return bots.values.sortedByDescending { it.createdAt }.take(limit.coerceIn(1, 500))
    }

    override fun botByFileName(fileName: String): ArenaBot? {
        val botId = botIdsByFileName[fileName] ?: return null
        return bots[botId]
    }

    override fun saveVersion(version: ArenaBotVersion) {
        versions[versionKey(version.botId, version.versionId)] = version
    }

    override fun version(botId: String, versionId: String): ArenaBotVersion? = versions[versionKey(botId, versionId)]

    override fun saveQualificationReport(report: ArenaQualificationReport) {
        reports.getOrPut(versionKey(report.botId, report.versionId)) { mutableListOf() }.add(report)
    }

    override fun qualificationReports(botId: String, versionId: String): List<ArenaQualificationReport> {
        return reports[versionKey(botId, versionId)]?.toList() ?: emptyList()
    }

    override fun saveOperatorDecision(decision: ArenaOperatorDecision) {
        decisions.getOrPut(versionKey(decision.botId, decision.versionId)) { mutableListOf() }.add(decision)
    }

    override fun operatorDecisions(botId: String, versionId: String): List<ArenaOperatorDecision> {
        return decisions[versionKey(botId, versionId)]?.toList() ?: emptyList()
    }

    override fun saveRunRecord(runRecord: ArenaRunRecord) {
        runs[runRecord.runId] = runRecord
    }

    override fun runRecord(runId: String): ArenaRunRecord? = runs[runId]

    override fun runs(limit: Int): List<ArenaRunRecord> {
        return runs.values.sortedByDescending { it.createdAt }.take(limit.coerceIn(1, 500))
    }

    override fun saveRunBotResult(result: ArenaRunBotResult) {
        val results = runBotResults.getOrPut(result.runId) { mutableListOf() }
        results.removeIf {
            it.botId == result.botId &&
                it.versionId == result.versionId &&
                it.scoringPolicyVersion == result.scoringPolicyVersion
        }
        results.add(result)
    }

    override fun runBotResults(runId: String): List<ArenaRunBotResult> {
        return runBotResults[runId]?.toList() ?: emptyList()
    }

    override fun saveRunEnforcementEvent(event: ArenaRunEnforcementEvent) {
        val events = runEnforcementEvents.getOrPut(event.runId) { mutableListOf() }
        events.removeIf {
            it.botId == event.botId &&
                it.versionId == event.versionId &&
                it.decision == event.decision &&
                it.reasonCode == event.reasonCode
        }
        events.add(event)
    }

    override fun runEnforcementEvents(runId: String): List<ArenaRunEnforcementEvent> {
        return runEnforcementEvents[runId]?.toList() ?: emptyList()
    }

    override fun leaderboard(
        modeId: String,
        scoringPolicyVersion: String,
        limit: Int
    ): List<ArenaLeaderboardEntry> {
        val eligibleRunIds = runs.values
            .filter { it.modeId == modeId && it.status == ArenaRunStatus.Completed }
            .map { it.runId }
            .toSet()
        return runBotResults.values
            .flatten()
            .filter { it.runId in eligibleRunIds && it.scoringPolicyVersion == scoringPolicyVersion }
            .filter { it.scoreEligible && it.publicLeaderboard && !it.disqualified }
            .sortedWith(
                compareByDescending<ArenaRunBotResult> { it.finalEquity }
                    .thenByDescending { it.realizedPnl }
                    .thenBy { it.maxDrawdown }
                    .thenBy { it.runId }
                    .thenBy { it.botId }
            )
            .take(limit)
            .mapIndexed { index, result ->
                val bot = bots[result.botId]
                ArenaLeaderboardEntry(
                    rank = index + 1,
                    runId = result.runId,
                    botId = result.botId,
                    botName = bot?.metadata?.name ?: result.botId,
                    ownerHandle = bot?.metadata?.publisher ?: "unknown",
                    versionId = result.versionId,
                    scoringPolicyVersion = result.scoringPolicyVersion,
                    finalEquity = result.finalEquity,
                    realizedPnl = result.realizedPnl,
                    maxDrawdown = result.maxDrawdown,
                    disqualified = result.disqualified
                )
            }
    }

    override fun replaceRuntimeConfigDescriptors(
        botId: String,
        versionId: String,
        descriptors: List<ArenaRuntimeConfigDescriptor>
    ) {
        runtimeConfigDescriptors[versionKey(botId, versionId)] = descriptors.toList()
    }

    override fun runtimeConfigDescriptors(botId: String, versionId: String): List<ArenaRuntimeConfigDescriptor> {
        return runtimeConfigDescriptors[versionKey(botId, versionId)]?.toList() ?: emptyList()
    }

    private fun versionKey(botId: String, versionId: String): String = "$botId:$versionId"
}

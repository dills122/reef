package com.reef.arena.controlplane.arena

interface ArenaBotRegistryStore {
    fun saveBot(bot: ArenaBot)
    fun bot(botId: String): ArenaBot?
    fun bots(limit: Int = 50): List<ArenaBot>
    /**
     * Batch lookup for a set of bot ids in a single round trip.
     *
     * Stores backed by a single query surface (e.g. Postgres) should override this
     * with one `WHERE bot_id = ANY(?)`-style query; the default keeps small
     * in-memory/test stores source-compatible.
     */
    fun botsByIds(botIds: List<String>): List<ArenaBot> = botIds.mapNotNull(::bot)
    fun botByFileName(fileName: String): ArenaBot?
    fun saveVersion(version: ArenaBotVersion)
    fun version(botId: String, versionId: String): ArenaBotVersion?
    fun saveQualificationReport(report: ArenaQualificationReport)
    fun qualificationReports(botId: String, versionId: String): List<ArenaQualificationReport>
    fun saveOperatorDecision(decision: ArenaOperatorDecision)
    /**
     * Persists a version state change with its mandatory operator decision.
     *
     * Stores with transaction support must override this so the version cannot
     * advance without the audit fact that explains it. The default keeps small
     * in-memory/test stores source-compatible.
     */
    fun saveVersionTransition(version: ArenaBotVersion, decision: ArenaOperatorDecision) {
        saveVersion(version)
        saveOperatorDecision(decision)
    }
    fun operatorDecisions(botId: String, versionId: String): List<ArenaOperatorDecision>
    fun saveRunRecord(runRecord: ArenaRunRecord)
    fun runRecord(runId: String): ArenaRunRecord?
    fun runs(limit: Int = 50): List<ArenaRunRecord>
    fun saveRunBotResult(result: ArenaRunBotResult)
    fun runBotResults(runId: String): List<ArenaRunBotResult>
    fun saveRunEnforcementEvent(event: ArenaRunEnforcementEvent)
    fun runEnforcementEvents(runId: String): List<ArenaRunEnforcementEvent>
    fun leaderboard(modeId: String, scoringPolicyVersion: String, limit: Int = 50): List<ArenaLeaderboardEntry>
    fun replaceRuntimeConfigDescriptors(
        botId: String,
        versionId: String,
        descriptors: List<ArenaRuntimeConfigDescriptor>
    )
    fun runtimeConfigDescriptors(botId: String, versionId: String): List<ArenaRuntimeConfigDescriptor>
}

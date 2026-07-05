package com.reef.platform.application.arena

class InMemoryArenaBotRegistryStore : ArenaBotRegistryStore {
    private val bots = linkedMapOf<String, ArenaBot>()
    private val botIdsByFileName = linkedMapOf<String, String>()
    private val versions = linkedMapOf<String, ArenaBotVersion>()
    private val reports = linkedMapOf<String, MutableList<ArenaQualificationReport>>()
    private val decisions = linkedMapOf<String, MutableList<ArenaOperatorDecision>>()

    override fun saveBot(bot: ArenaBot) {
        bots[bot.botId] = bot
        botIdsByFileName[bot.fileName] = bot.botId
    }

    override fun bot(botId: String): ArenaBot? = bots[botId]

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

    private fun versionKey(botId: String, versionId: String): String = "$botId:$versionId"
}

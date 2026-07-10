package com.reef.platform.application.analytics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.time.Instant

data class BotRunPerformanceSummaryRecord(
    val runId: String,
    val botId: String,
    val scenarioId: String,
    val profile: String,
    val source: String,
    val completedAt: Instant?,
    val exportedAt: Instant,
    val projectedAt: Instant,
    val finalEquity: Double?,
    val realizedPnl: Double?,
    val maxDrawdown: Double?,
    val failCount: Long,
    val commandCount: Long,
    val settlementScoreSummaryJson: String = "{}",
    val sourceSummaryJson: String = "{}"
)

interface BotRunPerformanceSummaryStore {
    fun upsertAll(records: List<BotRunPerformanceSummaryRecord>): Int
    fun list(runId: String = "", botId: String = "", limit: Int = 50): List<BotRunPerformanceSummaryRecord>
}

object BotRunPerformanceProjection {
    private val mapper = JsonMapper.builder().build()

    fun fromExport(export: SimulationRunExportRecord, projectedAt: Instant): List<BotRunPerformanceSummaryRecord> {
        val summary = parseObject(export.summaryJson)
        val settlementByParticipant = settlementScoreParticipants(summary)
        return summary.objectArray("botResults")
            .mapNotNull { bot ->
                val botId = bot.textAt(listOf("botId"), listOf("participantId"), listOf("actorId"), listOf("id"))
                    ?: return@mapNotNull null
                val commands = bot.atPath("tradingMetrics", "commands")
                val settlementSummary = bot.getObject("settlementScoreSummary") ?: settlementByParticipant[botId]
                BotRunPerformanceSummaryRecord(
                    runId = export.runId,
                    botId = botId,
                    scenarioId = export.scenarioId,
                    profile = export.profile,
                    source = export.source.ifBlank { "simulation-run-export" },
                    completedAt = export.completedAt,
                    exportedAt = export.exportedAt,
                    projectedAt = projectedAt,
                    finalEquity = bot.numberAt(
                        listOf("finalEquity"),
                        listOf("score"),
                        listOf("tradingMetrics", "pnl", "finalEquity"),
                        listOf("tradingMetrics", "pnl", "finalEquityDiagnostic"),
                        listOf("tradingMetrics", "pnl", "diagnosticFinalEquity")
                    ),
                    realizedPnl = bot.numberAt(
                        listOf("realizedPnl"),
                        listOf("realizedPnL"),
                        listOf("tradingMetrics", "pnl", "realized"),
                        listOf("tradingMetrics", "pnl", "realizedPnl"),
                        listOf("tradingMetrics", "pnl", "realizedPnL")
                    ),
                    maxDrawdown = bot.numberAt(
                        listOf("maxDrawdown"),
                        listOf("maxDrawdownValue"),
                        listOf("risk", "maxDrawdown"),
                        listOf("tradingMetrics", "pnl", "maxDrawdown")
                    ),
                    failCount = failCount(bot, commands),
                    commandCount = commandCount(bot, commands),
                    settlementScoreSummaryJson = writeObjectOrEmpty(settlementSummary),
                    sourceSummaryJson = mapper.writeValueAsString(bot)
                )
            }
    }

    private fun settlementScoreParticipants(summary: JsonNode): Map<String, JsonNode> {
        val score = summary.getObject("settlementScore") ?: summary.getObject("settlementScoreSummary") ?: return emptyMap()
        return score.objectArray("participants")
            .mapNotNull { participant ->
                val id = participant.textAt(listOf("participantId"), listOf("botId"), listOf("actorId"), listOf("id"))
                    ?: return@mapNotNull null
                id to participant
            }
            .toMap()
    }

    private fun failCount(bot: JsonNode, commands: JsonNode): Long {
        bot.longAt(listOf("failCount"), listOf("failedCount"), listOf("failures"))?.let { return it.coerceAtLeast(0L) }
        val commandFailures = listOf("failed", "rejected", "timedOut", "timeouts")
            .sumOf { key -> commands.longAt(listOf(key)) ?: 0L }
        val botFailures = listOf("failedTicks", "freezeCount")
            .sumOf { key -> bot.longAt(listOf(key)) ?: 0L }
        return (commandFailures + botFailures).coerceAtLeast(0L)
    }

    private fun commandCount(bot: JsonNode, commands: JsonNode): Long {
        return bot.longAt(listOf("commandCount"), listOf("venueCommands"), listOf("actionsProposed"))
            ?: commands.longAt(listOf("submitted"), listOf("proposed"), listOf("completed"))
            ?: 0L
    }

    private fun parseObject(raw: String): JsonNode {
        return try {
            val parsed = mapper.readTree(raw)
            if (parsed != null && parsed.isObject) parsed else mapper.createObjectNode()
        } catch (_: Exception) {
            mapper.createObjectNode()
        }
    }

    private fun writeObjectOrEmpty(node: JsonNode?): String {
        return if (node != null && node.isObject) mapper.writeValueAsString(node) else "{}"
    }
}

private fun JsonNode.getObject(field: String): JsonNode? {
    val node = get(field)
    return if (node != null && node.isObject) node else null
}

private fun JsonNode.atPath(vararg fields: String): JsonNode {
    var current = this
    for (field in fields) {
        val next = current.get(field) ?: return JsonMapper.builder().build().createObjectNode()
        current = next
    }
    return current
}

private fun JsonNode.objectArray(field: String): List<JsonNode> {
    val node = get(field)
    if (node == null || !node.isArray) return emptyList()
    return node.filter { it.isObject }
}

private fun JsonNode.textAt(vararg paths: List<String>): String? {
    for (path in paths) {
        val value = valueAt(path)
        if (value != null && !value.isNull) {
            val text = if (value.isTextual) value.textValue() else value.asText("")
            if (text.isNotBlank()) return text
        }
    }
    return null
}

private fun JsonNode.numberAt(vararg paths: List<String>): Double? {
    for (path in paths) {
        val value = valueAt(path)
        if (value != null && !value.isNull) {
            if (value.isNumber) return value.asDouble()
            if (value.isTextual) value.textValue().toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

private fun JsonNode.longAt(vararg paths: List<String>): Long? {
    for (path in paths) {
        val value = valueAt(path)
        if (value != null && !value.isNull) {
            if (value.isNumber) return value.asLong()
            if (value.isTextual) value.textValue().toLongOrNull()?.let { return it }
        }
    }
    return null
}

private fun JsonNode.valueAt(path: List<String>): JsonNode? {
    var current: JsonNode = this
    for (field in path) {
        current = current.get(field) ?: return null
    }
    return current
}

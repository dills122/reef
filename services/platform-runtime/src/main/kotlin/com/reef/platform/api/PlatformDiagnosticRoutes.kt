package com.reef.platform.api

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics

internal class PlatformDiagnosticRoutes(
    private val healthJson: () -> String,
    private val abuseStatsJson: () -> String,
    private val dbPoolStatsJson: () -> String,
    private val asyncCommandStatsJson: () -> String,
    private val commandAccountingJson: (String) -> String,
    private val streamCommandHealthJson: () -> String,
    private val streamCommandWorkerStatsJson: () -> String,
    private val projectorStatusJson: () -> String
) {
    val paths: List<String> = listOf(
        "/health",
        "/internal/boundary/abuse/stats",
        "/internal/perf/hot-path",
        "/internal/perf/db-pools",
        "/internal/commands/async/stats",
        "/internal/commands/accounting",
        "/internal/stream-ack/health",
        "/internal/stream-ack/worker/stats",
        "/internal/projector/status"
    )

    fun handle(method: String, path: String, query: String?): PlatformHotPathResponse? {
        return when (path) {
            "/health" -> getOnly(method) { healthJson() }
            "/internal/boundary/abuse/stats" -> getOnly(method) { abuseStatsJson() }
            "/internal/perf/hot-path" -> hotPathMetrics(method)
            "/internal/perf/db-pools" -> getOnly(method) { dbPoolStatsJson() }
            "/internal/commands/async/stats" -> getOnly(method) { asyncCommandStatsJson() }
            "/internal/commands/accounting" -> getOnly(method) {
                commandAccountingJson(queryValue(query, "runId"))
            }
            "/internal/stream-ack/health" -> getOnly(method) { streamCommandHealthJson() }
            "/internal/stream-ack/worker/stats" -> getOnly(method) { streamCommandWorkerStatsJson() }
            "/internal/projector/status" -> getOnly(method) { projectorStatusJson() }
            else -> null
        }
    }

    private fun hotPathMetrics(method: String): PlatformHotPathResponse {
        return when (method) {
            "GET" -> PlatformHotPathResponse(200, JsonCodec.writeObject("metrics" to HotPathMetrics.snapshot()))
            "POST" -> {
                HotPathMetrics.reset()
                PlatformHotPathResponse(200, JsonCodec.writeObject("status" to "reset"))
            }
            else -> methodNotAllowedResponse()
        }
    }

    private fun getOnly(method: String, body: () -> String): PlatformHotPathResponse {
        return if (method == "GET") {
            PlatformHotPathResponse(200, body())
        } else {
            methodNotAllowedResponse()
        }
    }
}

internal fun methodNotAllowedResponse(): PlatformHotPathResponse {
    return PlatformHotPathResponse(status = 405, body = "", contentType = null)
}

internal fun queryValue(query: String?, key: String): String {
    if (query.isNullOrBlank()) return ""
    val values = query.split("&")
    for (value in values) {
        val parts = value.split("=", limit = 2)
        if (parts.size == 2 && parts[0] == key) {
            return parts[1]
        }
    }
    return ""
}

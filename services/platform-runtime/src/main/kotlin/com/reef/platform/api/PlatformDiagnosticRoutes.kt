package com.reef.platform.api

import com.reef.platform.infrastructure.diagnostics.HotPathMetrics

internal class PlatformDiagnosticRoutes(
    private val healthJson: () -> String,
    private val abuseStatsJson: () -> String,
    private val accountRiskControlsJson: () -> String,
    private val accountRiskDecisionsJson: (Int) -> String,
    private val commandCircuitBreakersJson: () -> String,
    private val instrumentPriceCollarsJson: () -> String,
    private val setAccountRiskControlJson: (String) -> PlatformHotPathResponse,
    private val setCommandCircuitBreakerJson: (String) -> PlatformHotPathResponse,
    private val setInstrumentPriceCollarJson: (String) -> PlatformHotPathResponse,
    private val registerArenaBotJson: (String) -> PlatformHotPathResponse,
    private val registerArenaBotVersionJson: (String) -> PlatformHotPathResponse,
    private val transitionArenaBotVersionJson: (String) -> PlatformHotPathResponse,
    private val dbPoolStatsJson: () -> String,
    private val asyncCommandStatsJson: () -> String,
    private val commandAccountingJson: (String) -> String,
    private val streamCommandHealthJson: () -> String,
    private val streamCommandWorkerStatsJson: () -> String,
    private val venueEventMaterializerStatsJson: () -> String,
    private val projectorStatusJson: () -> String,
    private val marketDataProjectorStatsJson: () -> String
) {
    val paths: List<String> = listOf(
        "/health",
        "/internal/boundary/abuse/stats",
        "/internal/boundary/account-risk/controls",
        "/internal/boundary/account-risk/decisions/recent",
        "/internal/boundary/circuit-breakers",
        "/internal/boundary/price-collars",
        "/internal/admin/account-risk/controls",
        "/internal/admin/circuit-breakers",
        "/internal/admin/price-collars",
        "/internal/admin/arena/bots",
        "/internal/admin/arena/bot-versions",
        "/internal/admin/arena/bot-versions/transition",
        "/internal/perf/hot-path",
        "/internal/perf/db-pools",
        "/internal/commands/async/stats",
        "/internal/commands/accounting",
        "/internal/stream-ack/health",
        "/internal/stream-ack/worker/stats",
        "/internal/venue-event-materializer/stats",
        "/internal/projector/status",
        "/internal/market-data/projector/status"
    )

    fun handle(method: String, path: String, query: String?, body: String = ""): PlatformHotPathResponse? {
        return when (path) {
            "/health" -> getOnly(method) { healthJson() }
            "/internal/boundary/abuse/stats" -> getOnly(method) { abuseStatsJson() }
            "/internal/boundary/account-risk/controls" -> getOnly(method) { accountRiskControlsJson() }
            "/internal/boundary/account-risk/decisions/recent" -> getOnly(method) {
                accountRiskDecisionsJson(queryValue(query, "limit").toIntOrNull() ?: 50)
            }
            "/internal/boundary/circuit-breakers" -> getOnly(method) { commandCircuitBreakersJson() }
            "/internal/boundary/price-collars" -> getOnly(method) { instrumentPriceCollarsJson() }
            "/internal/admin/account-risk/controls" -> postOnly(method) { setAccountRiskControlJson(body) }
            "/internal/admin/circuit-breakers" -> postOnly(method) { setCommandCircuitBreakerJson(body) }
            "/internal/admin/price-collars" -> postOnly(method) { setInstrumentPriceCollarJson(body) }
            "/internal/admin/arena/bots" -> postOnly(method) { registerArenaBotJson(body) }
            "/internal/admin/arena/bot-versions" -> postOnly(method) { registerArenaBotVersionJson(body) }
            "/internal/admin/arena/bot-versions/transition" -> postOnly(method) {
                transitionArenaBotVersionJson(body)
            }
            "/internal/perf/hot-path" -> hotPathMetrics(method)
            "/internal/perf/db-pools" -> getOnly(method) { dbPoolStatsJson() }
            "/internal/commands/async/stats" -> getOnly(method) { asyncCommandStatsJson() }
            "/internal/commands/accounting" -> getOnly(method) {
                commandAccountingJson(queryValue(query, "runId"))
            }
            "/internal/stream-ack/health" -> getOnly(method) { streamCommandHealthJson() }
            "/internal/stream-ack/worker/stats" -> getOnly(method) { streamCommandWorkerStatsJson() }
            "/internal/venue-event-materializer/stats" -> getOnly(method) { venueEventMaterializerStatsJson() }
            "/internal/projector/status" -> getOnly(method) { projectorStatusJson() }
            "/internal/market-data/projector/status" -> getOnly(method) { marketDataProjectorStatsJson() }
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

    private fun postOnly(method: String, response: () -> PlatformHotPathResponse): PlatformHotPathResponse {
        return if (method == "POST") {
            response()
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
